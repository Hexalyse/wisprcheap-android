package io.github.hexalyse.wisprcheap.core.settings

import java.net.URI

/** A problem that prevents a feature from working, shown as a setup item on Home. */
data class SetupIssue(
    val path: String,
    val message: String,
    /** True when dictation can't work at all until it's fixed. */
    val blocking: Boolean,
)

object Validation {
    /** Missing keys and out-of-range values. The UI prevents most range errors; this guards imported data. */
    fun issues(s: Settings, keys: ApiKeys): List<SetupIssue> {
        val out = mutableListOf<SetupIssue>()
        fun check(ok: Boolean, path: String, msg: String, blocking: Boolean = true) {
            if (!ok) out += SetupIssue(path, msg, blocking)
        }

        when (s.transcription.provider) {
            Provider.ELEVENLABS -> check(
                keys.elevenlabs.isNotBlank() || isLocal(s.transcription.elevenlabs.baseUrl),
                "transcription.elevenlabs.apiKey", "Add your ElevenLabs API key",
            )
            Provider.OPENAI -> check(
                keys.openai.isNotBlank() || isLocal(s.transcription.openai.baseUrl),
                "transcription.openai.apiKey", "Add your OpenAI API key",
            )
        }
        if (s.polish.enabled) {
            val o = LlmResolve.polish(s, keys)
            check(
                o.apiKey.isNotEmpty() || isLocal(o.baseUrl), "polish.apiKey",
                "Cleanup needs an API key (or turn cleanup off)", blocking = false,
            )
        }
        if (s.command.enabled) {
            val o = LlmResolve.command(s, keys)
            check(
                o.apiKey.isNotEmpty() || isLocal(o.baseUrl), "command.apiKey",
                "Command mode needs an API key (or turn it off)", blocking = false,
            )
        }
        if (s.translation.pairs.isNotEmpty()) {
            val o = LlmResolve.translation(s, keys)
            check(
                o.apiKey.isNotEmpty() || isLocal(o.baseUrl), "translation.apiKey",
                "Translation needs an API key", blocking = false,
            )
        }

        // Range problems are reported but never block dictation (the pipeline copes with odd values).
        fun range(ok: Boolean, path: String, msg: String) = check(ok, path, msg, blocking = false)
        range(s.transcription.timeoutMs > 0, "transcription.timeoutMs", "Must be greater than 0")
        range(s.polish.timeoutMs > 0, "polish.timeoutMs", "Must be greater than 0")
        range(s.command.timeoutMs > 0, "command.timeoutMs", "Must be greater than 0")
        range(s.translation.timeoutMs > 0, "translation.timeoutMs", "Must be greater than 0")
        range(s.recording.maxDurationSec > 0, "recording.maxDurationSec", "Must be greater than 0")
        range(s.recording.silenceThresholdDb <= 0, "recording.silenceThresholdDb", "Must be 0 or less")
        range(s.polish.temperature?.let { it in 0.0..2.0 } ?: true, "polish.temperature", "Between 0 and 2")
        range(
            s.command.llm.temperature?.let { it in 0.0..2.0 } ?: true, "command.temperature", "Between 0 and 2",
        )
        range(
            s.translation.llm.temperature?.let { it in 0.0..2.0 } ?: true, "translation.temperature",
            "Between 0 and 2",
        )
        range(s.polish.instructions.isNotBlank(), "polish.instructions", "Cleanup instructions must not be empty")
        range(s.bubble.tapMaxMs >= s.bubble.micStartDelayMs, "bubble.tapMaxMs", "Must be ≥ the hold delay")
        return out
    }

    /**
     * No API key needed: localhost, 127.0.0.1, private IPv4 ranges (10/8, 172.16/12, 192.168/16) and `.local`
     * hosts, e.g. an Ollama server on the LAN.
     */
    fun isLocal(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()
            ?: return url.contains("localhost") || url.contains("127.0.0.1")
        if (host == "localhost" || host.endsWith(".local") || host == "127.0.0.1" || host == "[::1]") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }
}
