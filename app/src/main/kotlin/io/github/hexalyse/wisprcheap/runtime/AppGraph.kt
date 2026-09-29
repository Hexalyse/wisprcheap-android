package io.github.hexalyse.wisprcheap.runtime

import android.app.Application
import io.github.hexalyse.wisprcheap.BuildConfig
import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.llm.ChatClient
import io.github.hexalyse.wisprcheap.core.pipeline.Delivery
import io.github.hexalyse.wisprcheap.core.pipeline.DeliveryResult
import io.github.hexalyse.wisprcheap.core.pipeline.Job
import io.github.hexalyse.wisprcheap.core.pipeline.JobQueue
import io.github.hexalyse.wisprcheap.core.pipeline.Pipeline
import io.github.hexalyse.wisprcheap.core.pipeline.PipelineDeps
import io.github.hexalyse.wisprcheap.core.pipeline.PipelineEvent
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.Validation
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import io.github.hexalyse.wisprcheap.data.FailedAudioFiles
import io.github.hexalyse.wisprcheap.data.HistoryRepository
import io.github.hexalyse.wisprcheap.data.LogStore
import io.github.hexalyse.wisprcheap.data.SecretStore
import io.github.hexalyse.wisprcheap.data.SettingsRepository
import io.github.hexalyse.wisprcheap.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/** Application-wide singletons (manual dependency injection). */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = LogStore(File(app.filesDir, "logs"))
    val settings = SettingsRepository(File(app.filesDir, "settings.json"), scope) { log.error(it) }
    val secrets = SecretStore(File(app.filesDir, "secrets.bin"), scope) { log.error(it) }
    val history: HistoryRepository = HistoryRepository(
        File(app.filesDir, "history.jsonl"),
        persist = { settings.current.history.enabled },
        deviceId = { sync.deviceId },
        onDeleted = { sync.historyDeleted(it) },
    )
    val failedAudio = FailedAudioFiles(File(app.filesDir, "recordings"))
    val state = AppState()
    val notifier = Notifier(app, settings)
    val http = Http.client("wisprcheap-android/${BuildConfig.VERSION_NAME}")
    val chat = ChatClient(http)
    val sync: SyncManager = SyncManager(app, settings, secrets, { history }, http, log, scope)

    /** Set by the accessibility service while it runs; otherwise results only go to the clipboard. */
    @Volatile var delivery: Delivery? = null

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val clipboardDelivery = Delivery { req ->
        Clipboard.set(app, req.text, settings.current.output.hideClipboardPreview)
        DeliveryResult(Delivered.CLIPBOARD, "clipboard")
    }

    private val deps = PipelineDeps(
        delivery = { req -> (delivery ?: clipboardDelivery).deliver(req) },
        history = history,
        failedAudio = failedAudio,
        listener = ::onPipelineEvent,
        log = log,
    )

    val queue = JobQueue(
        scope,
        runner = { job ->
            state.busyLabel.value = "Transcribing..."
            Pipeline.create(job.settings, secrets.current, chat, http, deps).run(job)
        },
        onError = { log.error("Unexpected error: ${it.message ?: it}") },
    )

    fun enqueue(job: Job) = queue.enqueue(job)

    private fun onPipelineEvent(e: PipelineEvent) {
        when (e) {
            is PipelineEvent.Done -> {
                state.lastText.value = e.text
                if (e.retry) {
                    state.lastFailed.value = null
                    notifier.info("Retry succeeded", "The text was copied to the clipboard.")
                }
            }
            is PipelineEvent.TranscriptionFailed -> {
                if (!e.command) state.lastFailed.value = LastFailed(e.pcm, e.message, e.audioFile, System.currentTimeMillis())
                notifier.error(
                    if (e.command) "Command failed" else "Transcription failed",
                    e.message + if (e.command) "" else "\nTap Retry to try again.",
                    retry = !e.command,
                )
            }
            is PipelineEvent.TranslationFailed -> notifier.error("Translation failed", "${e.message}\nThe untranslated text was inserted.")
            is PipelineEvent.CommandFailed -> notifier.error("Command failed", e.message)
            is PipelineEvent.DeliveryFailed -> notifier.error("Could not insert the text", e.message)
            PipelineEvent.MicSilenced -> notifier.error(
                "Microphone silenced",
                "Android returned an empty recording. Another app may be using the microphone.",
            )
            is PipelineEvent.Busy -> state.busyLabel.value = e.label
            is PipelineEvent.Discarded -> Unit
        }
        _events.tryEmit(e)
    }

    /** Retries the last failed recording (in memory); the result goes to the clipboard. */
    fun retryLastFailed() {
        val failed = state.lastFailed.value ?: run {
            log.info("Nothing to retry.")
            return
        }
        log.info("Retrying the last failed recording...")
        enqueue(retryJob(failed.pcm))
    }

    /** Retries a failed history entry from its saved audio. */
    fun retryEntry(entry: HistoryEntry): Boolean {
        val pcm = entry.audioFile?.let(failedAudio::read) ?: return false
        log.info("Retrying the recording of ${entry.ts}...")
        enqueue(retryJob(pcm))
        return true
    }

    private fun retryJob(pcm: ShortArray): Job {
        val s = settings.current
        return Job.Dictation(pcm, s, targetApp = null, translation = TranslationPairs.active(s.translation), retry = true)
    }

    fun startup() {
        scope.launch(Dispatchers.IO) {
            val deleted = failedAudio.cleanup(settings.current.history.failedAudioRetentionDays)
            if (deleted > 0) log.info("Deleted $deleted saved recording(s) older than ${settings.current.history.failedAudioRetentionDays} days.")
        }
        banner(settings.current)
        sync.start()
    }

    private fun banner(s: Settings) {
        val t = s.transcription
        val model = if (t.provider.id == "elevenlabs") t.elevenlabs.model else t.openai.model
        val pairs = TranslationPairs.build(s.translation.pairs).first
        log.info("WisprCheap ${BuildConfig.VERSION_NAME} ready")
        log.detail("  transcribe: ${t.provider.id} / $model (language: ${t.language})")
        log.detail(
            "  cleanup:    " + if (s.polish.enabled) {
                "${s.polish.model} @ ${Http.trimSlash(s.polish.baseUrl).substringAfter("://").substringBefore('/')}" +
                    if (s.polish.minWords > 0) " (skipped under ${s.polish.minWords} words)" else ""
            } else {
                "off"
            },
        )
        log.detail("  command:    " + if (s.command.enabled) (s.command.llm.model ?: s.polish.model) else "off")
        log.detail(
            "  translate:  " + if (pairs.isEmpty()) "none configured" else pairs.joinToString(", ") { it.label } +
                " (currently: ${TranslationPairs.active(s.translation)?.label ?: "off"})",
        )
        log.detail("  dictionary: ${s.dictionary.size} term(s)")
        sync.status.value.let { st ->
            log.detail("  sync:       " + if (sync.connected) "${st.server} as \"${st.deviceName}\"" else "off")
        }
        Validation.issues(s, secrets.current).forEach { log.detail("  setup:      ${it.message}") }
    }
}
