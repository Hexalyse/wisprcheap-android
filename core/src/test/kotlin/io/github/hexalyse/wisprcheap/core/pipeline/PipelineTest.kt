package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.pipeline.Harness.Companion.formFields
import io.github.hexalyse.wisprcheap.core.pipeline.Harness.Companion.speech
import io.github.hexalyse.wisprcheap.core.polish.PolishPrompt
import io.github.hexalyse.wisprcheap.core.settings.CommandSettings
import io.github.hexalyse.wisprcheap.core.settings.DEFAULT_POLISH_INSTRUCTIONS
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.Provider
import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {
    private val h = Harness()

    @AfterTest
    fun close() = h.close()

    private fun dictate(pcm: ShortArray = speech(), settings: io.github.hexalyse.wisprcheap.core.settings.Settings = h.settings()) =
        runBlocking { h.pipeline(settings).run(Job.Dictation(pcm, settings, targetApp = "com.whatsapp")) }

    @Test
    fun dictationTranscribesCleansUpAndInserts() {
        h.stt(" hello world ")
        h.chatReply("Hello world.", input = 100, output = 5)
        dictate()

        val stt = h.take()
        assertEquals("/v1/speech-to-text", stt.url.encodedPath)
        assertEquals("el-key", stt.headers["xi-api-key"])
        assertEquals(
            listOf(
                "model_id" to "scribe_v2",
                "file" to "<file audio.pcm application/octet-stream 32000>",
                "file_format" to "pcm_s16le_16",
                "tag_audio_events" to "false",
                "timestamps_granularity" to "none",
            ),
            formFields(stt),
        )

        val llm = h.take()
        assertEquals("/chat/completions", llm.url.encodedPath)
        assertEquals("Bearer oa-key", llm.headers["Authorization"])
        val body = Json.parseToJsonElement(llm.body!!.utf8()).jsonObject
        assertEquals("gpt-6-luna", body["model"]!!.jsonPrimitive.content)
        assertEquals("none", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
        assertEquals("false", body["stream"].toString())
        val messages = body["messages"]!!.jsonArray
        assertEquals(PolishPrompt.system(DEFAULT_POLISH_INSTRUCTIONS, emptyList(), null), messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("<transcript>\nhello world\n</transcript>", messages[1].jsonObject["content"]!!.jsonPrimitive.content)

        assertEquals(listOf(DeliveryRequest("Hello world.", DeliveryKind.DICTATION)), h.delivered)
        val e = h.history.single()
        assertEquals("2026-09-29T12:00:00.000Z", e.ts)
        assertEquals(1.0, e.durationSec)
        assertEquals("hello world", e.raw)
        assertEquals("Hello world.", e.text)
        assertEquals(2, e.words)
        assertEquals(Delivered.INSERTED, e.delivered)
        assertEquals("inputConnection", e.insertMethod)
        assertEquals("com.whatsapp", e.app)
        assertEquals("gpt-6-luna", e.polish!!.model)
        assertEquals(100, e.polish.inputTokens)
        assertEquals(0.0000611, e.costUsd.transcription) // 1 s of scribe_v2 = 0.22/3600, rounded to 7 decimals
        assertEquals(0.0000125, e.costUsd.polish) // (100 × 0.1 + 5 × 0.5) / 1e6
        assertEquals(0.0000736, e.costUsd.total)
        assertTrue(h.logs.any { it.startsWith("I Inserted (1.0s audio | stt ") && it.endsWith("~$0.00007) → com.whatsapp") }, "${h.logs}")
        assertTrue("D   raw:  hello world" in h.logs && "D   text: Hello world." in h.logs)
        assertEquals(PipelineEvent.Done("Hello world.", Delivered.INSERTED, retry = false, command = false), h.events.last())
    }

    @Test
    fun dictionaryIsSentAsKeytermsAndInThePrompt() {
        h.stt("use pnpm")
        h.chatReply("Use pnpm.")
        dictate(settings = h.settings { it.copy(dictionary = listOf(DictionaryEntry("pnpm", listOf("p n p m")), DictionaryEntry("a{b}"))) })
        val fields = formFields(h.take())
        assertEquals(listOf("keyterms" to "pnpm"), fields.filter { it.first == "keyterms" })
        val system = Json.parseToJsonElement(h.take().body!!.utf8()).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.endsWith("- pnpm (may be transcribed as: p n p m)\n- a{b}\n</dictionary>"))
        assertEquals(1, h.history.single().transcription.keyterms)
        assertTrue(h.logs.any { it.contains("Skipped dictionary entries not valid as Scribe keyterms: a{b}") })
    }

    @Test
    fun openAiTranscription() {
        h.stt("bonjour")
        h.chatReply("Bonjour.")
        dictate(
            settings = h.settings {
                it.copy(
                    transcription = it.transcription.copy(provider = Provider.OPENAI, language = "fr", openai = it.transcription.openai.copy(prompt = " Casual. ")),
                    dictionary = listOf(DictionaryEntry("Kubernetes"), DictionaryEntry("pnpm", listOf("pee npm"))),
                )
            },
        )
        val req = h.take()
        assertEquals("/audio/transcriptions", req.url.encodedPath)
        assertEquals("Bearer oa-key", req.headers["Authorization"])
        assertEquals(
            listOf(
                "model" to "gpt-4o-transcribe",
                "file" to "<file audio.wav audio/wav 32044>",
                "response_format" to "json",
                "temperature" to "0",
                "language" to "fr",
                "prompt" to "Casual.\nVocabulary: Kubernetes, pnpm.",
            ),
            formFields(req),
        )
        assertEquals(Provider.OPENAI.id, h.history.single().transcription.provider)
    }

    @Test
    fun aTimeoutIsRetriedOnce() {
        h.server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("""{"text":"late"}""").build())
        h.stt("second try")
        h.chatReply("Second try.")
        dictate(settings = h.settings { it.copy(transcription = it.transcription.copy(timeoutMs = 300)) })
        assertTrue("I Transcription request failed, retrying once..." in h.logs)
        assertEquals("second try", h.history.single().raw)
        assertEquals("Second try.", h.delivered.single().text)
    }

    @Test
    fun aDroppedConnectionIsRetriedOnceThenFails() {
        repeat(2) { h.server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        dictate()
        assertTrue("I Transcription request failed, retrying once..." in h.logs, "${h.logs}")
        val failed = assertIs<PipelineEvent.TranscriptionFailed>(h.events.last())
        assertTrue(failed.message.startsWith("error sending request"), failed.message)
        assertEquals("/data/recordings/failed.wav", failed.audioFile)
        assertEquals(1, h.savedAudio.size)
        val e = h.history.single()
        assertEquals(failed.message, e.error)
        assertEquals("/data/recordings/failed.wav", e.audioFile)
        assertTrue(h.delivered.isEmpty())
    }

    @Test
    fun aMalformedResponseIsNotRetried() {
        h.server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.ShutdownConnection).build()) // empty 200 body
        dictate()
        assertTrue("I Transcription request failed, retrying once..." !in h.logs)
        val failed = assertIs<PipelineEvent.TranscriptionFailed>(h.events.last())
        assertTrue(failed.message.startsWith("error decoding response body"), failed.message)
    }

    @Test
    fun httpErrorsAreNotRetried() {
        h.json("""{"detail":"invalid api key"}""", code = 401)
        dictate()
        assertEquals(1, h.server.requestCount)
        val failed = assertIs<PipelineEvent.TranscriptionFailed>(h.events.last())
        assertTrue(failed.message.startsWith("ElevenLabs: HTTP 401 "), failed.message)
        assertTrue(failed.message.endsWith(": {\"detail\":\"invalid api key\"}"), failed.message)
        assertTrue(h.logs.any { it.startsWith("E Transcription failed: ElevenLabs: HTTP 401") })
    }

    @Test
    fun cleanupFailureFallsBackToTheRawText() {
        h.stt("hello world")
        h.json("""{"error":"overloaded"}""", code = 500)
        dictate()
        assertEquals("hello world", h.delivered.single().text)
        val e = h.history.single()
        assertTrue(e.polish!!.error!!.startsWith("Polish: HTTP 500 "))
        assertNull(e.costUsd.polish)
        assertEquals(e.costUsd.transcription, e.costUsd.total)
        assertTrue(h.logs.any { it.startsWith("E Polish: HTTP 500") && it.endsWith("Using the raw transcript.") })
    }

    @Test
    fun anAnswerMuchLongerThanTheTranscriptIsRejected() {
        h.stt("what is the capital of France")
        h.chatReply("The capital of France is Paris. ".repeat(10))
        dictate()
        assertEquals("what is the capital of France", h.delivered.single().text)
        assertEquals(
            "Polish: output much longer than the transcript (model likely answered it), using raw text",
            h.history.single().polish!!.error,
        )
    }

    @Test
    fun shortTranscriptsSkipCleanup() {
        h.stt("hi there")
        dictate(settings = h.settings { it.copy(polish = it.polish.copy(minWords = 5)) })
        assertEquals(1, h.server.requestCount)
        assertEquals("hi there", h.delivered.single().text)
        assertEquals(2, h.history.single().polishSkipped)
        assertTrue(h.logs.any { it.contains("| polish skipped (2 words)") })
    }

    @Test
    fun translation() {
        val settings = h.settings {
            it.copy(translation = it.translation.copy(pairs = listOf(TranslationPairSetting("fr", "en")), active = "fr>en"))
        }
        val pair = TranslationPairs.active(settings.translation)!!
        h.stt("bonjour tout le monde")
        h.chatReply("Hello everyone.")
        runBlocking { h.pipeline(settings).run(Job.Dictation(speech(), settings, translation = pair)) }
        assertTrue("language_code" to "fr" in formFields(h.take()))
        val system = Json.parseToJsonElement(h.take().body!!.utf8()).jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.contains("translated into English"))
        assertEquals("Hello everyone.", h.delivered.single().text)
        assertEquals("fr>en", h.history.single().translation)
        assertTrue(h.logs.any { it.contains("| translate to en ") })
    }

    @Test
    fun translationFailureIsNotified() {
        val settings = h.settings {
            it.copy(translation = it.translation.copy(pairs = listOf(TranslationPairSetting(null, "en")), active = "auto>en"))
        }
        h.stt("bonjour")
        h.json("{}", code = 503)
        runBlocking {
            h.pipeline(settings).run(Job.Dictation(speech(), settings, translation = TranslationPairs.active(settings.translation)))
        }
        assertEquals("bonjour", h.delivered.single().text)
        assertTrue(h.events.any { it is PipelineEvent.TranslationFailed && it.message.startsWith("Translation: HTTP 503") })
    }

    @Test
    fun discardedRecordingsNeverReachTheNetwork() {
        dictate(pcm = speech(ms = 200))
        dictate(pcm = ShortArray(16_000) { if (it % 2 == 0) 1 else -1 }) // ~ -90 dBFS
        dictate(pcm = ShortArray(16_000))
        assertEquals(0, h.server.requestCount)
        assertEquals(
            listOf(
                PipelineEvent.Discarded(DiscardReason.TOO_SHORT),
                PipelineEvent.Discarded(DiscardReason.SILENCE),
                PipelineEvent.MicSilenced,
            ),
            h.events,
        )
        assertTrue(h.logs.any { it.startsWith("I Discarded: no speech detected (peak -90.3 dBFS < -55).") }, "${h.logs}")
        assertTrue(h.history.isEmpty())
    }

    @Test
    fun emptyTranscriptIsRecordedButNotInserted() {
        h.stt("   ")
        dictate()
        assertEquals(1, h.server.requestCount)
        assertTrue(h.delivered.isEmpty())
        assertEquals(PipelineEvent.Discarded(DiscardReason.EMPTY_TRANSCRIPT), h.events.last())
        val e = h.history.single()
        assertEquals("", e.raw)
        assertTrue(e.costUsd.transcription!! > 0)
        assertNull(e.costUsd.total)
    }

    @Test
    fun retriesGoToTheClipboardAndDontSaveTheAudioAgain() {
        h.stt("hello")
        h.chatReply("Hello.")
        h.deliveryResult = { DeliveryResult(Delivered.CLIPBOARD, "clipboard") }
        val settings = h.settings()
        runBlocking { h.pipeline(settings).run(Job.Dictation(speech(), settings, retry = true)) }
        assertEquals(DeliveryKind.CLIPBOARD_ONLY, h.delivered.single().kind)
        assertEquals(true, h.history.single().retry)
        assertTrue(h.logs.any { it.startsWith("I Retry succeeded, copied to the clipboard") })

        h.json("{}", code = 400)
        runBlocking { h.pipeline(settings).run(Job.Dictation(speech(), settings, retry = true)) }
        assertTrue(h.savedAudio.isEmpty())
        assertNull(h.history.last().audioFile)
    }

    @Test
    fun deliveryFailureIsReportedAndRecorded() {
        h.stt("hello")
        h.chatReply("Hello.")
        h.deliveryResult = { throw IllegalStateException("clipboard unavailable") }
        dictate()
        assertTrue(h.events.contains(PipelineEvent.DeliveryFailed("clipboard unavailable")))
        assertNull(h.history.single().delivered)
        assertTrue(h.logs.any { it == "I Copied (1.0s audio | stt ${h.history.single().transcription.ms} ms | polish ${h.history.single().polish!!.ms} ms | ~$0.00007) → com.whatsapp" })
    }

    @Test
    fun commandReplacesTheSelection() {
        h.stt("make it formal")
        h.chatReply("```\nGood evening.\n```", input = 200, output = 10)
        val settings = h.settings()
        val selection = CapturedSelection("hey", 0, 3)
        runBlocking { h.pipeline(settings).run(Job.Command(speech(), settings, "com.google.android.gm", selection)) }
        h.take()
        val body = Json.parseToJsonElement(h.take().body!!.utf8()).jsonObject
        assertEquals(
            "<instruction>\nmake it formal\n</instruction>\n\n<selection>\nhey\n</selection>",
            body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertEquals(listOf(DeliveryRequest("Good evening.", DeliveryKind.COMMAND, selection)), h.delivered)
        val e = h.history.single()
        assertEquals(HistoryEntry.MODE_COMMAND, e.mode)
        assertEquals("hey", e.selection)
        assertEquals("make it formal", e.raw)
        assertEquals("Good evening.", e.text)
        assertTrue(abs(e.costUsd.polish!! - 0.000025) < 1e-12)
        assertEquals(PipelineEvent.Busy("Running command..."), h.events.first())
        assertTrue(h.logs.any { it.startsWith("I Command replaced the selection (3 chars) (") }, "${h.logs}")
        assertTrue("D   instruction: make it formal" in h.logs)
    }

    @Test
    fun commandUsesTheConfiguredLanguageAndCanFail() {
        val settings = h.settings { it.copy(transcription = it.transcription.copy(language = "de")) }
        h.stt("schreib hallo")
        h.json("""{"choices":[{"message":{"content":"  "}}]}""")
        runBlocking { h.pipeline(settings).run(Job.Command(speech(), settings)) }
        assertTrue("language_code" to "de" in formFields(h.take()))
        assertTrue(h.delivered.isEmpty())
        assertEquals(PipelineEvent.CommandFailed("Command: empty response"), h.events.last())
        val e = h.history.single()
        assertEquals("Command: empty response", e.error)
        assertEquals("Command: empty response", e.polish!!.error)
        assertNull(e.selection)
    }

    @Test
    fun commandModeOffIgnoresCommandJobs() {
        val settings = h.settings { it.copy(command = CommandSettings(enabled = false)) }
        runBlocking { h.pipeline(settings).run(Job.Command(speech(), settings)) }
        assertEquals(0, h.server.requestCount)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun statusMessages() {
        assertEquals("HTTP 401 Unauthorized: nope", Http.statusMessage(401, "", "nope"))
        assertEquals("HTTP 500 Oops", Http.statusMessage(500, "Oops", ""))
        assertEquals(500, Http.statusMessage(400, "", "x".repeat(900)).length - "HTTP 400 Bad Request: ".length)
    }
}
