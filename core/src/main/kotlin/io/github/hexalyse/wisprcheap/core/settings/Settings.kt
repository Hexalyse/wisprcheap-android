package io.github.hexalyse.wisprcheap.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * All user settings except API keys (see [ApiKeys]). Stored as one JSON document.
 * Keys mirror the desktop config.yaml where the option exists; defaults are the desktop defaults.
 */
@Serializable
data class Settings(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val transcription: TranscriptionSettings = TranscriptionSettings(),
    val polish: PolishSettings = PolishSettings(),
    val dictionary: List<DictionaryEntry> = emptyList(),
    val command: CommandSettings = CommandSettings(),
    val translation: TranslationSettings = TranslationSettings(),
    val recording: RecordingSettings = RecordingSettings(),
    val bubble: BubbleSettings = BubbleSettings(),
    val output: OutputSettings = OutputSettings(),
    val history: HistorySettings = HistorySettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val pricing: PricingSettings = PricingSettings(),
) {
    companion object {
        const val CURRENT_SCHEMA = 1

        /** Lenient on read (unknown keys ignored, missing keys = defaults); nulls are kept (null ≠ default). */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun decode(text: String): Settings = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}

const val OPENAI_BASE_URL = "https://api.openai.com/v1"
const val ELEVENLABS_BASE_URL = "https://api.elevenlabs.io"

const val DEFAULT_POLISH_INSTRUCTIONS =
    "Remove filler words, repeated starts, and abandoned phrases. When I correct myself, keep the final version. " +
        "Fix punctuation and capitalization. Preserve my meaning, wording, names, and numbers."

@Serializable
enum class Provider(val id: String) {
    @SerialName("elevenlabs") ELEVENLABS("elevenlabs"),
    @SerialName("openai") OPENAI("openai"),
}

@Serializable
data class TranscriptionSettings(
    val provider: Provider = Provider.ELEVENLABS,
    /** ISO code or "auto". */
    val language: String = "auto",
    val timeoutMs: Long = 30_000,
    val elevenlabs: ElevenLabsSettings = ElevenLabsSettings(),
    val openai: OpenAiTranscriptionSettings = OpenAiTranscriptionSettings(),
)

@Serializable
data class ElevenLabsSettings(
    val baseUrl: String = ELEVENLABS_BASE_URL,
    val model: String = "scribe_v2",
    /** Send the dictionary as Scribe keyterms (+$0.05/h). */
    val keyterms: Boolean = true,
    /** Let Scribe remove filler words itself. */
    val noVerbatim: Boolean = false,
)

@Serializable
data class OpenAiTranscriptionSettings(
    val baseUrl: String = OPENAI_BASE_URL,
    val model: String = "gpt-4o-transcribe",
    /** Extra context; the dictionary is appended as "Vocabulary: …". */
    val prompt: String = "",
)

@Serializable
data class PolishSettings(
    val enabled: Boolean = true,
    val baseUrl: String = OPENAI_BASE_URL,
    val model: String = "gpt-6-luna",
    /** null = the field is omitted from the request. */
    val reasoningEffort: String? = "none",
    /** null = the field is omitted; otherwise 0..2. */
    val temperature: Double? = null,
    val timeoutMs: Long = 10_000,
    /** Skip cleanup for transcripts shorter than this many words (0 = always clean up). */
    val minWords: Int = 0,
    val instructions: String = DEFAULT_POLISH_INSTRUCTIONS,
)

/**
 * LLM settings for command and translation: null / inherit = same as the cleanup (polish) settings.
 * `reasoningEffort`/`temperature` are tri-state: inherit, omit (value null), or a value.
 */
@Serializable
data class LlmOverride(
    val baseUrl: String? = null,
    val model: String? = null,
    val inheritReasoningEffort: Boolean = true,
    val reasoningEffort: String? = null,
    val inheritTemperature: Boolean = true,
    val temperature: Double? = null,
)

@Serializable
data class CommandSettings(
    val enabled: Boolean = true,
    val llm: LlmOverride = LlmOverride(),
    val timeoutMs: Long = 30_000,
)

@Serializable
data class TranslationPairSetting(
    /** Spoken language code, or null to auto-detect. */
    val from: String? = null,
    val to: String,
)

@Serializable
data class TranslationSettings(
    val pairs: List<TranslationPairSetting> = emptyList(),
    val llm: LlmOverride = LlmOverride(),
    val timeoutMs: Long = 15_000,
    /** Id of the active pair (e.g. "fr>en"), or null when translation is off. */
    val active: String? = null,
)

@Serializable
enum class AudioSourceSetting {
    @SerialName("voiceRecognition") VOICE_RECOGNITION,
    @SerialName("mic") MIC,
    @SerialName("unprocessed") UNPROCESSED,
}

@Serializable
data class RecordingSettings(
    val minDurationMs: Long = 300,
    val tailMs: Long = 150,
    val maxDurationSec: Double = 600.0,
    val silenceThresholdDb: Double = -55.0,
    val audioSource: AudioSourceSetting = AudioSourceSetting.VOICE_RECOGNITION,
)

@Serializable
enum class ShowWhen {
    /** Keyboard visible and an editor active (default). */
    @SerialName("editing") EDITING,
    @SerialName("editorActive") EDITOR_ACTIVE,
    @SerialName("keyboardVisible") KEYBOARD_VISIBLE,
    @SerialName("always") ALWAYS,
}

@Serializable
enum class BubbleSize(val dp: Int) {
    @SerialName("s") S(40),
    @SerialName("m") M(48),
    @SerialName("l") L(56),
}

@Serializable
data class BubbleSettings(
    val paused: Boolean = false,
    val showWhen: ShowWhen = ShowWhen.EDITING,
    val size: BubbleSize = BubbleSize.M,
    val idleOpacity: Float = 0.9f,
    val followKeyboard: Boolean = true,
    val micStartDelayMs: Long = 200,
    val tapMaxMs: Long = 300,
    val commandSlideDp: Int = 64,
    val cancelSlideDp: Int = 96,
    val hideOnPasswordFields: Boolean = true,
    val excludedApps: List<String> = emptyList(),
    val haptics: Boolean = true,
    val hapticOnInsert: Boolean = false,
    val keepScreenOnWhileRecording: Boolean = true,
)

@Serializable
enum class InsertMethod {
    @SerialName("auto") AUTO,
    @SerialName("inputConnection") INPUT_CONNECTION,
    @SerialName("setText") SET_TEXT,
    @SerialName("paste") PASTE,
    @SerialName("clipboard") CLIPBOARD,
}

@Serializable
data class OutputSettings(
    val insertMethod: InsertMethod = InsertMethod.AUTO,
    /** Append a space after dictations (used when smart spacing can't see the surrounding text). */
    val trailingSpace: Boolean = true,
    val smartSpacing: Boolean = true,
    /** Learned or pinned insertion method per app package. */
    val perApp: Map<String, InsertMethod> = emptyMap(),
    /** Mark our clipboard entries as sensitive (hides the Android 13+ copy preview). */
    val hideClipboardPreview: Boolean = true,
)

@Serializable
data class HistorySettings(
    val enabled: Boolean = true,
    val saveFailedAudio: Boolean = true,
    val failedAudioRetentionDays: Int = 30,
    val recordTargetApp: Boolean = true,
    val logDictatedText: Boolean = true,
)

@Serializable
data class NotificationSettings(
    val errors: Boolean = true,
)

@Serializable
data class PriceOverride(
    /** Transcription models: USD per audio minute. */
    val perMinute: Double? = null,
    /** LLM models: USD per million input / output tokens. */
    val inputPerM: Double? = null,
    val outputPerM: Double? = null,
)

@Serializable
data class PricingSettings(
    val overrides: Map<String, PriceOverride> = emptyMap(),
)

@Serializable
data class DictionaryEntry(
    val term: String,
    val soundsLike: List<String> = emptyList(),
)

/** API keys, stored encrypted apart from [Settings]. Empty = not set. */
data class ApiKeys(
    val elevenlabs: String = "",
    val openai: String = "",
    /** Cleanup LLM key. Empty = the OpenAI key when the cleanup endpoint is on the same host. */
    val polish: String = "",
    /** Empty = same as the cleanup key. */
    val command: String = "",
    /** Empty = same as the cleanup key. */
    val translation: String = "",
) {
    override fun toString() = "ApiKeys(***)"
}
