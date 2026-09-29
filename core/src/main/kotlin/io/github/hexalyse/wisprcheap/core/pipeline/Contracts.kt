package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.translate.TranslationPair
import java.time.Instant

/** Work queued after a recording. [settings] is the snapshot taken when the recording started. */
sealed class Job(val pcm: ShortArray, val settings: Settings, val targetApp: String?) {
    class Dictation(
        pcm: ShortArray,
        settings: Settings,
        targetApp: String? = null,
        /** Translation pair active when the recording started, or null. */
        val translation: TranslationPair? = null,
        /** A retry of a failed recording: the result only goes to the clipboard. */
        val retry: Boolean = false,
    ) : Job(pcm, settings, targetApp)

    class Command(
        pcm: ShortArray,
        settings: Settings,
        targetApp: String? = null,
        /** Selection captured when the recording started, or null for "no selection". */
        val selection: CapturedSelection? = null,
    ) : Job(pcm, settings, targetApp)
}

/** Selected text and its absolute offsets in the editor. */
data class CapturedSelection(val text: String, val start: Int, val end: Int)

enum class DeliveryKind {
    /** Insert at the cursor with the spacing rules. */
    DICTATION,

    /** Replace the captured selection (or insert at the cursor), no extra spaces. */
    COMMAND,

    /** Clipboard only (retries: the target field is unknown). */
    CLIPBOARD_ONLY,
}

data class DeliveryRequest(
    val text: String,
    val kind: DeliveryKind,
    val selection: CapturedSelection? = null,
    val smartSpacing: Boolean = true,
    val trailingSpace: Boolean = true,
)

data class DeliveryResult(val delivered: Delivered, val method: String? = null)

/** Inserts text in the focused field (implemented by the accessibility service). Throws when even the clipboard failed. */
fun interface Delivery {
    suspend fun deliver(request: DeliveryRequest): DeliveryResult
}

/** Stores history entries (Room in the app) and updates the month totals. */
fun interface HistoryStore {
    suspend fun add(entry: HistoryEntry)
}

/** Saves the audio of a failed transcription; returns its path, or null. */
fun interface FailedAudioStore {
    fun save(pcm: ShortArray, startedAt: Instant): String?
}

/** Log lines in the desktop format: `info`/`error` get a time prefix, `detail` lines don't. */
interface PipelineLog {
    fun info(message: String)
    fun error(message: String)
    fun detail(message: String)
}

enum class DiscardReason { TOO_SHORT, SILENCE, EMPTY_TRANSCRIPT, EMPTY_INSTRUCTION }

/** What the UI reacts to: haptics, bubble state, notifications, "last dictation" / "last failed". */
sealed interface PipelineEvent {
    data class Discarded(val reason: DiscardReason) : PipelineEvent

    /** The recording is only zeros: Android silenced the microphone. */
    data object MicSilenced : PipelineEvent

    data class Busy(val label: String) : PipelineEvent

    class TranscriptionFailed(
        val message: String,
        val pcm: ShortArray,
        val audioFile: String?,
        val retry: Boolean,
        val command: Boolean,
    ) : PipelineEvent

    data class TranslationFailed(val message: String) : PipelineEvent
    data class CommandFailed(val message: String) : PipelineEvent
    data class DeliveryFailed(val message: String) : PipelineEvent

    /** Text produced and delivered (or not, if [delivered] is null). */
    data class Done(val text: String, val delivered: Delivered?, val retry: Boolean, val command: Boolean) : PipelineEvent
}

fun interface PipelineListener {
    fun onEvent(event: PipelineEvent)
}
