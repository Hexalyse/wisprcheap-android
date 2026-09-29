package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.audio.Pcm
import io.github.hexalyse.wisprcheap.core.command.Commander
import io.github.hexalyse.wisprcheap.core.dictionary.Dictionary
import io.github.hexalyse.wisprcheap.core.history.Costs
import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.Money
import io.github.hexalyse.wisprcheap.core.history.PolishInfo
import io.github.hexalyse.wisprcheap.core.history.Timestamps
import io.github.hexalyse.wisprcheap.core.history.TranscriptionInfo
import io.github.hexalyse.wisprcheap.core.history.round2
import io.github.hexalyse.wisprcheap.core.history.round7
import io.github.hexalyse.wisprcheap.core.http.NetworkException
import io.github.hexalyse.wisprcheap.core.llm.ChatClient
import io.github.hexalyse.wisprcheap.core.polish.Polisher
import io.github.hexalyse.wisprcheap.core.pricing.Pricing
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.LlmResolve
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.stt.Transcriber
import io.github.hexalyse.wisprcheap.core.text.Words
import io.github.hexalyse.wisprcheap.core.translate.TranslationPair
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.Locale

data class PipelineDeps(
    val delivery: Delivery,
    val history: HistoryStore,
    val failedAudio: FailedAudioStore,
    val listener: PipelineListener,
    val log: PipelineLog,
    val clock: () -> Instant = Instant::now,
)

/**
 * Processing of one recording (port of the desktop `jobs.rs`): checks, transcription (one retry on network
 * errors), cleanup or translation (raw text on any failure), delivery, history and logs.
 */
class Pipeline(
    private val settings: Settings,
    private val transcriber: Transcriber,
    private val chat: ChatClient,
    keys: ApiKeys,
    private val deps: PipelineDeps,
) {
    private val log = deps.log
    private val dictionary = Dictionary.normalize(settings.dictionary)
    private val pricing = Pricing(settings.pricing.overrides)
    private val polisher: Polisher? = if (settings.polish.enabled) {
        Polisher(chat, LlmResolve.polish(settings, keys), settings.polish.instructions, dictionary)
    } else {
        null
    }
    private val translationLlm = LlmResolve.translation(settings, keys)
    private val commander: Commander? =
        if (settings.command.enabled) Commander(chat, LlmResolve.command(settings, keys), dictionary) else null

    suspend fun run(job: Job) = when (job) {
        is Job.Dictation -> dictation(job)
        is Job.Command -> command(job)
    }

    /** Common checks before sending audio anywhere. Returns false (and reports why) when it should be dropped. */
    private fun usable(pcm: ShortArray): Boolean {
        val durationMs = Pcm.durationMs(pcm.size)
        if (durationMs < settings.recording.minDurationMs) {
            log.info("Discarded: too short (${Math.round(durationMs)} ms).")
            deps.listener.onEvent(PipelineEvent.Discarded(DiscardReason.TOO_SHORT))
            return false
        }
        if (Pcm.isAllZero(pcm)) {
            log.error("Discarded: the microphone was silenced by Android (the recording is empty).")
            deps.listener.onEvent(PipelineEvent.MicSilenced)
            return false
        }
        val level = Pcm.loudestWindowDb(pcm)
        val threshold = settings.recording.silenceThresholdDb
        if (level < threshold) {
            log.info(
                "Discarded: no speech detected (peak ${"%.1f".format(Locale.ROOT, level)} dBFS < ${number(threshold)}).",
            )
            deps.listener.onEvent(PipelineEvent.Discarded(DiscardReason.SILENCE))
            return false
        }
        return true
    }

    private fun newEntry(job: Job, startedAt: Instant) = HistoryEntry(
        ts = Timestamps.iso(startedAt),
        durationSec = round2(Pcm.durationMs(job.pcm.size) / 1000.0),
        transcription = TranscriptionInfo(transcriber.provider.id, transcriber.model, 0, transcriber.keytermCount),
        app = if (settings.history.recordTargetApp) job.targetApp else null,
    )

    /** Transcribes with one retry on network errors/timeouts; fills the transcription fields of [entry]. */
    private suspend fun transcribe(pcm: ShortArray, entry: HistoryEntry, language: String?): Pair<String, HistoryEntry> {
        val t0 = System.nanoTime()
        val raw = try {
            transcriber.transcribe(pcm, language)
        } catch (e: NetworkException) {
            log.info("Transcription request failed, retrying once...")
            transcriber.transcribe(pcm, language)
        }
        return raw to entry.copy(
            transcription = entry.transcription.copy(ms = elapsedMs(t0)),
            raw = raw,
            costUsd = Costs(
                transcription = pricing.transcriptionCost(transcriber.model, entry.durationSec, transcriber.keytermCount),
            ),
        )
    }

    private suspend fun dictation(job: Job.Dictation) {
        val startedAt = deps.clock()
        if (!usable(job.pcm)) return
        var entry = newEntry(job, startedAt).copy(
            retry = if (job.retry) true else null,
            translation = job.translation?.id,
        )
        val translator = job.translation?.let(::translator)

        val raw: String
        try {
            val (text, updated) = transcribe(job.pcm, entry, job.translation?.from)
            raw = text
            entry = updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            val audioFile = if (!job.retry && settings.history.saveFailedAudio) {
                runCatching { deps.failedAudio.save(job.pcm, startedAt) }.getOrNull()
            } else {
                null
            }
            deps.history.add(entry.copy(error = message, audioFile = audioFile))
            log.error(
                "Transcription failed: $message" + (audioFile?.let { "\n  Audio saved to $it" } ?: "") +
                    "\n  Use \"Retry\" in the app or in the notification to try again.",
            )
            deps.listener.onEvent(PipelineEvent.TranscriptionFailed(message, job.pcm, audioFile, job.retry, false))
            return
        }

        if (raw.isEmpty()) {
            log.info("Discarded: the transcript is empty.")
            deps.history.add(roundCosts(entry))
            deps.listener.onEvent(PipelineEvent.Discarded(DiscardReason.EMPTY_TRANSCRIPT))
            return
        }

        // Translate, or clean up (short transcripts skip cleanup). Falls back to the raw transcript on any failure.
        var text = raw
        val words = Words.count(raw)
        val minWords = settings.polish.minWords
        val skipPolish = translator == null && minWords > 0 && words < minWords
        val llm = translator ?: if (skipPolish) null else polisher
        if (skipPolish && polisher != null) entry = entry.copy(polishSkipped = words)
        if (llm != null) {
            val t1 = System.nanoTime()
            var info = PolishInfo(model = llm.model)
            try {
                val result = llm.polish(raw)
                text = result.text
                info = info.copy(inputTokens = result.inputTokens, outputTokens = result.outputTokens)
                entry = entry.copy(
                    costUsd = entry.costUsd.copy(polish = pricing.llmCost(llm.model, result.inputTokens, result.outputTokens)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.toString()
                log.error("$message\n  Using the raw transcript.")
                if (translator != null) deps.listener.onEvent(PipelineEvent.TranslationFailed(message))
                info = info.copy(error = message)
            }
            entry = entry.copy(polish = info.copy(ms = elapsedMs(t1)))
        }

        val kind = if (job.retry) DeliveryKind.CLIPBOARD_ONLY else DeliveryKind.DICTATION
        val result = deliver(
            DeliveryRequest(
                text, kind, smartSpacing = settings.output.smartSpacing, trailingSpace = settings.output.trailingSpace,
            ),
        )
        entry = finish(entry.copy(delivered = result?.delivered, insertMethod = result?.method), text)
        deps.history.add(entry)

        val action = when {
            job.retry -> "Retry succeeded, copied to the clipboard"
            result?.delivered == Delivered.INSERTED -> "Inserted"
            result?.delivered == Delivered.PASTED -> "Pasted"
            else -> "Copied"
        }
        val llmLabel = if (translator != null) "translate to ${job.translation.to}" else "polish"
        log.info("$action (${timingSummary(entry, llmLabel)})" + (job.targetApp?.let { " → $it" } ?: ""))
        if (settings.history.logDictatedText) {
            if (text != raw) log.detail("  raw:  $raw")
            log.detail("  text: $text")
        }
        deps.listener.onEvent(PipelineEvent.Done(text, result?.delivered, job.retry, command = false))
    }

    private suspend fun command(job: Job.Command) {
        val commander = commander ?: return
        val startedAt = deps.clock()
        if (!usable(job.pcm)) return
        deps.listener.onEvent(PipelineEvent.Busy("Running command..."))
        var entry = newEntry(job, startedAt).copy(mode = HistoryEntry.MODE_COMMAND, selection = job.selection?.text)

        val instruction: String
        try {
            val (text, updated) = transcribe(job.pcm, entry, null)
            instruction = text
            entry = updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            deps.history.add(entry.copy(error = message))
            log.error("Command: transcription failed: $message")
            deps.listener.onEvent(PipelineEvent.CommandFailed("Transcription failed: $message"))
            return
        }

        if (instruction.isEmpty()) {
            log.info("Command discarded: the instruction is empty.")
            deps.history.add(roundCosts(entry))
            deps.listener.onEvent(PipelineEvent.Discarded(DiscardReason.EMPTY_INSTRUCTION))
            return
        }

        val t1 = System.nanoTime()
        var info = PolishInfo(model = commander.model)
        val text = try {
            val result = commander.run(instruction, job.selection?.text)
            info = info.copy(inputTokens = result.inputTokens, outputTokens = result.outputTokens)
            entry = entry.copy(
                costUsd = entry.costUsd.copy(polish = pricing.llmCost(commander.model, result.inputTokens, result.outputTokens)),
            )
            result.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            deps.history.add(roundCosts(entry.copy(polish = info.copy(ms = elapsedMs(t1), error = message), error = message)))
            log.error("$message\n  instruction: $instruction")
            deps.listener.onEvent(PipelineEvent.CommandFailed(message))
            return
        }
        entry = entry.copy(polish = info.copy(ms = elapsedMs(t1)))

        val result = deliver(DeliveryRequest(text, DeliveryKind.COMMAND, selection = job.selection))
        entry = finish(entry.copy(delivered = result?.delivered, insertMethod = result?.method), text)
        deps.history.add(entry)

        val target = job.selection?.let { "replaced the selection (${it.text.length} chars)" } ?: "inserted at the cursor"
        val what = if (result?.delivered == Delivered.INSERTED || result?.delivered == Delivered.PASTED) target else "result copied"
        log.info("Command $what (${timingSummary(entry, "llm")})")
        if (settings.history.logDictatedText) {
            log.detail("  instruction: $instruction")
            log.detail("  result:      ${if (text.length > 300) text.take(300) + "..." else text}")
        }
        deps.listener.onEvent(PipelineEvent.Done(text, result?.delivered, retry = false, command = true))
    }

    private suspend fun deliver(request: DeliveryRequest): DeliveryResult? = try {
        deps.delivery.deliver(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val message = e.message ?: e.toString()
        log.error("Could not insert the text: $message")
        deps.listener.onEvent(PipelineEvent.DeliveryFailed(message))
        null
    }

    private fun translator(pair: TranslationPair): Polisher =
        Polisher(chat, translationLlm, settings.polish.instructions, dictionary, translateTo = pair.toName)

    companion object {
        fun create(settings: Settings, keys: ApiKeys, chat: ChatClient, http: okhttp3.OkHttpClient, deps: PipelineDeps): Pipeline {
            val dictionary = Dictionary.normalize(settings.dictionary)
            val transcriber = Transcriber.create(settings, keys, dictionary, http) { deps.log.detail(it) }
            return Pipeline(settings, transcriber, chat, keys, deps)
        }

        /** Final text, word count and rounded costs (total = transcription + cleanup; null if transcription unknown). */
        fun finish(entry: HistoryEntry, text: String): HistoryEntry {
            val t = entry.costUsd.transcription
            val p = entry.costUsd.polish
            return entry.copy(
                text = text,
                words = Words.count(text),
                costUsd = Costs(
                    transcription = t?.let(::round7),
                    polish = p?.let(::round7),
                    total = t?.let { round7(it + (p ?: 0.0)) },
                ),
            )
        }

        private fun roundCosts(entry: HistoryEntry) = entry.copy(
            costUsd = entry.costUsd.copy(
                transcription = entry.costUsd.transcription?.let(::round7),
                polish = entry.costUsd.polish?.let(::round7),
            ),
        )

        /** "3.2s audio | stt 812 ms | polish 640 ms | ~$0.00030". */
        fun timingSummary(entry: HistoryEntry, llmLabel: String): String {
            val llm = entry.polish?.let { " | $llmLabel ${it.ms} ms" }
                ?: entry.polishSkipped?.let { " | polish skipped ($it words)" }
                ?: ""
            val cost = entry.costUsd.total?.let { " | ${Money.log(it)}" } ?: ""
            return "%.1fs audio | stt %d ms%s%s".format(Locale.ROOT, entry.durationSec, entry.transcription.ms, llm, cost)
        }

        private fun elapsedMs(t0: Long) = (System.nanoTime() - t0) / 1_000_000

        /** Like JavaScript's number formatting: "-55" rather than "-55.0". */
        private fun number(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    }
}
