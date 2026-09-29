package io.github.hexalyse.wisprcheap.core.settings

import java.net.URI

/** Connection settings for one OpenAI-compatible Chat Completions call. */
data class LlmOptions(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    /** null = omitted from the request. */
    val reasoningEffort: String?,
    /** null = omitted from the request. */
    val temperature: Double?,
    val timeoutMs: Long,
) {
    override fun toString() =
        "LlmOptions(baseUrl=$baseUrl, model=$model, reasoningEffort=$reasoningEffort, temperature=$temperature, " +
            "timeoutMs=$timeoutMs, apiKey=${if (apiKey.isEmpty()) "none" else "***"})"
}

/** Resolves the LLM settings of each step, applying the fallbacks to the cleanup (polish) settings. */
object LlmResolve {
    fun polish(s: Settings, keys: ApiKeys): LlmOptions {
        val p = s.polish
        return LlmOptions(
            apiKey = polishKey(s, keys),
            baseUrl = p.baseUrl,
            model = p.model,
            reasoningEffort = p.reasoningEffort,
            temperature = p.temperature,
            timeoutMs = p.timeoutMs,
        )
    }

    fun command(s: Settings, keys: ApiKeys): LlmOptions =
        resolve(s.command.llm, keys.command, s, keys, s.command.timeoutMs)

    fun translation(s: Settings, keys: ApiKeys): LlmOptions =
        resolve(s.translation.llm, keys.translation, s, keys, s.translation.timeoutMs)

    /** The cleanup key, or the OpenAI key when the cleanup endpoint is on the OpenAI transcription host. */
    fun polishKey(s: Settings, keys: ApiKeys): String = keys.polish.ifBlank {
        if (sameHost(s.polish.baseUrl, s.transcription.openai.baseUrl)) keys.openai else ""
    }.trim()

    private fun resolve(o: LlmOverride, key: String, s: Settings, keys: ApiKeys, timeoutMs: Long): LlmOptions {
        val p = s.polish
        return LlmOptions(
            apiKey = key.ifBlank { polishKey(s, keys) }.trim(),
            baseUrl = o.baseUrl?.takeIf { it.isNotBlank() } ?: p.baseUrl,
            model = o.model?.takeIf { it.isNotBlank() } ?: p.model,
            reasoningEffort = if (o.inheritReasoningEffort) p.reasoningEffort else o.reasoningEffort,
            temperature = if (o.inheritTemperature) p.temperature else o.temperature,
            timeoutMs = timeoutMs,
        )
    }

    fun sameHost(a: String, b: String): Boolean {
        val ha = host(a) ?: return false
        return ha == host(b)
    }

    fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
}
