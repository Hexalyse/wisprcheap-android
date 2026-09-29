package io.github.hexalyse.wisprcheap.core.stt

import io.github.hexalyse.wisprcheap.core.audio.Pcm
import io.github.hexalyse.wisprcheap.core.dictionary.Dictionary
import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.ElevenLabsSettings
import io.github.hexalyse.wisprcheap.core.settings.OpenAiTranscriptionSettings
import io.github.hexalyse.wisprcheap.core.settings.Provider
import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Speech-to-text for 16 kHz mono PCM16. Port of the desktop `transcribe.rs`. */
interface Transcriber {
    val provider: Provider
    val model: String

    /** Number of dictionary terms actually sent to the API (for cost estimates). */
    val keytermCount: Int

    /** [language]: spoken language for this request (e.g. from a translation pair), or null for the setting. */
    suspend fun transcribe(pcm: ShortArray, language: String? = null): String

    companion object {
        /** Builds the configured transcriber. [warn] receives the desktop's keyterm warnings. */
        fun create(
            settings: Settings,
            keys: ApiKeys,
            dictionary: List<DictionaryEntry>,
            http: OkHttpClient,
            warn: (String) -> Unit = {},
        ): Transcriber {
            val t = settings.transcription
            return when (t.provider) {
                Provider.ELEVENLABS -> {
                    val (keyterms, skipped) =
                        if (t.elevenlabs.keyterms) Keyterms.split(dictionary) else emptyList<String>() to emptyList()
                    if (skipped.isNotEmpty()) {
                        warn("[transcribe] Skipped dictionary entries not valid as Scribe keyterms: ${skipped.joinToString(", ")}")
                    }
                    if (keyterms.size > 100) {
                        warn("[transcribe] ${keyterms.size} keyterms: ElevenLabs bills each request at least 20 s above 100 keyterms.")
                    }
                    ElevenLabsTranscriber(http, t.elevenlabs, keys.elevenlabs.trim(), t.language, t.timeoutMs, keyterms)
                }
                Provider.OPENAI -> OpenAiTranscriber(
                    http, t.openai, keys.openai.trim(), t.language, t.timeoutMs,
                    OpenAiTranscriber.prompt(t.openai.prompt, dictionary),
                )
            }
        }
    }
}

object Keyterms {
    const val MAX = 1000

    /** Scribe keyterm rules: < 50 chars, at most 5 words, none of `<>{}[]\`, max 1000 terms. */
    fun split(dictionary: List<DictionaryEntry>): Pair<List<String>, List<String>> {
        val keyterms = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (entry in dictionary) {
            if (Dictionary.isValidKeyterm(entry.term) && keyterms.size < MAX) keyterms += entry.term else skipped += entry.term
        }
        return keyterms to skipped
    }
}

private fun parseText(body: String): String =
    Http.parseJson(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""

class ElevenLabsTranscriber(
    private val http: OkHttpClient,
    private val opts: ElevenLabsSettings,
    private val apiKey: String,
    private val defaultLanguage: String,
    private val timeoutMs: Long,
    val keyterms: List<String>,
) : Transcriber {
    override val provider = Provider.ELEVENLABS
    override val model: String get() = opts.model
    override val keytermCount: Int get() = keyterms.size

    override suspend fun transcribe(pcm: ShortArray, language: String?): String {
        val lang = language ?: defaultLanguage
        // Raw 16 kHz mono s16le is accepted directly and gives lower latency than an encoded file.
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", opts.model)
            .addFormDataPart("file", "audio.pcm", Pcm.toBytes(pcm).toRequestBody(OCTET_STREAM))
            .addFormDataPart("file_format", "pcm_s16le_16")
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("timestamps_granularity", "none")
        if (lang != "auto") form.addFormDataPart("language_code", lang)
        if (opts.noVerbatim) form.addFormDataPart("no_verbatim", "true")
        for (term in keyterms) form.addFormDataPart("keyterms", term)
        val request = Request.Builder()
            .url("${Http.trimSlash(opts.baseUrl)}/v1/speech-to-text")
            .header("xi-api-key", apiKey)
            .post(form.build())
            .build()
        return parseText(Http.fetch(http, request, timeoutMs, "ElevenLabs"))
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

class OpenAiTranscriber(
    private val http: OkHttpClient,
    private val opts: OpenAiTranscriptionSettings,
    private val apiKey: String,
    private val defaultLanguage: String,
    private val timeoutMs: Long,
    val prompt: String,
) : Transcriber {
    override val provider = Provider.OPENAI
    override val model: String get() = opts.model
    override val keytermCount = 0

    override suspend fun transcribe(pcm: ShortArray, language: String?): String {
        val lang = language ?: defaultLanguage
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", opts.model)
            .addFormDataPart("file", "audio.wav", Pcm.encodeWav(pcm).toRequestBody(WAV))
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
        if (lang != "auto") form.addFormDataPart("language", lang)
        if (prompt.isNotEmpty()) form.addFormDataPart("prompt", prompt)
        val request = Request.Builder()
            .url("${Http.trimSlash(opts.baseUrl)}/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(form.build())
            .build()
        return parseText(Http.fetch(http, request, timeoutMs, "OpenAI transcription"))
    }

    companion object {
        private val WAV = "audio/wav".toMediaType()

        /** The configured prompt plus "Vocabulary: t1, t2." (terms only, no soundsLike hints). */
        fun prompt(configured: String, dictionary: List<DictionaryEntry>): String {
            val vocabulary = if (dictionary.isEmpty()) "" else "Vocabulary: ${dictionary.joinToString(", ") { it.term }}."
            return listOf(configured.trim(), vocabulary).filter { it.isNotEmpty() }.joinToString("\n")
        }
    }
}
