package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.llm.ChatClient
import io.github.hexalyse.wisprcheap.core.settings.ElevenLabsSettings
import io.github.hexalyse.wisprcheap.core.settings.LlmOptions
import io.github.hexalyse.wisprcheap.core.stt.ElevenLabsTranscriber
import okhttp3.OkHttpClient
import okhttp3.Request

/** "Test" buttons of the API key settings: the cheapest real call per endpoint. */
object ConnectionTest {
    /** Transcribes 1 s of near-silence (≈ $0.00006). Returns the latency in ms. */
    suspend fun elevenLabs(http: OkHttpClient, settings: ElevenLabsSettings, apiKey: String): Long = timed {
        val pcm = ShortArray(16_000) { if (it % 2 == 0) 2 else -2 }
        ElevenLabsTranscriber(http, settings, apiKey.trim(), "auto", 20_000, emptyList()).transcribe(pcm)
    }

    /** Lists the models (free). */
    suspend fun openAiModels(http: OkHttpClient, baseUrl: String, apiKey: String): Long = timed {
        val request = Request.Builder()
            .url("${Http.trimSlash(baseUrl)}/models")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
            .get()
            .build()
        Http.fetch(http, request, 20_000, "OpenAI")
    }

    /** A tiny chat completion with the given settings. */
    suspend fun chat(chat: ChatClient, opts: LlmOptions, label: String): Long = timed {
        val r = chat.complete(opts.copy(timeoutMs = maxOf(opts.timeoutMs, 20_000)), "Reply with the single word: ok", "ping", label)
        if (r.text.isBlank()) throw io.github.hexalyse.wisprcheap.core.http.ApiException("$label: empty response")
    }

    private inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }
}
