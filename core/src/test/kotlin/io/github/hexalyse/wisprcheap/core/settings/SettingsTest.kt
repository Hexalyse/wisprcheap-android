package io.github.hexalyse.wisprcheap.core.settings

import io.github.hexalyse.wisprcheap.core.dictionary.Dictionary
import io.github.hexalyse.wisprcheap.core.stt.Keyterms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    @Test
    fun defaultsMatchTheDesktop() {
        val s = Settings()
        assertEquals(Provider.ELEVENLABS, s.transcription.provider)
        assertEquals("auto", s.transcription.language)
        assertEquals(30_000, s.transcription.timeoutMs)
        assertEquals("scribe_v2", s.transcription.elevenlabs.model)
        assertEquals("gpt-4o-transcribe", s.transcription.openai.model)
        assertEquals("gpt-6-luna", s.polish.model)
        assertEquals("none", s.polish.reasoningEffort)
        assertNull(s.polish.temperature)
        assertEquals(10_000, s.polish.timeoutMs)
        assertEquals(30_000, s.command.timeoutMs)
        assertEquals(15_000, s.translation.timeoutMs)
        assertEquals(300, s.recording.minDurationMs)
        assertEquals(150, s.recording.tailMs)
        assertEquals(600.0, s.recording.maxDurationSec)
        assertEquals(-55.0, s.recording.silenceThresholdDb)
        assertEquals(200, s.bubble.micStartDelayMs)
        assertEquals(300, s.bubble.tapMaxMs)
        assertTrue(s.output.trailingSpace)
    }

    @Test
    fun jsonRoundTripKeepsExplicitNulls() {
        val s = Settings(polish = PolishSettings(reasoningEffort = null, temperature = 0.3))
        val back = Settings.decode(s.encode())
        assertEquals(s, back)
        assertNull(back.polish.reasoningEffort) // null = omit, must not come back as the "none" default
    }

    @Test
    fun missingAndUnknownKeysAreTolerated() {
        val s = Settings.decode("""{"polish":{"model":"gpt-5-mini"},"somethingNew":42}""")
        assertEquals("gpt-5-mini", s.polish.model)
        assertEquals("none", s.polish.reasoningEffort)
        assertEquals(Provider.ELEVENLABS, s.transcription.provider)
    }

    @Test
    fun polishKeyFallsBackToOpenAiOnlyOnTheSameHost() {
        val keys = ApiKeys(openai = "oa")
        assertEquals("oa", LlmResolve.polish(Settings(), keys).apiKey)
        val groq = Settings(polish = PolishSettings(baseUrl = "https://api.groq.com/openai/v1"))
        assertEquals("", LlmResolve.polish(groq, keys).apiKey)
        assertEquals("own", LlmResolve.polish(groq, keys.copy(polish = "own")).apiKey)
    }

    @Test
    fun commandAndTranslationInheritFromPolish() {
        val s = Settings(
            polish = PolishSettings(model = "m1", reasoningEffort = "low", temperature = 0.5, timeoutMs = 1),
            command = CommandSettings(llm = LlmOverride(model = "m2", inheritTemperature = false, temperature = null)),
            translation = TranslationSettings(llm = LlmOverride(inheritReasoningEffort = false, reasoningEffort = null)),
        )
        val keys = ApiKeys(openai = "oa", translation = "tr")
        val c = LlmResolve.command(s, keys)
        assertEquals("m2", c.model)
        assertEquals("low", c.reasoningEffort)
        assertNull(c.temperature) // explicitly omitted
        assertEquals("oa", c.apiKey)
        assertEquals(30_000, c.timeoutMs) // own timeout
        val t = LlmResolve.translation(s, keys)
        assertEquals("m1", t.model)
        assertNull(t.reasoningEffort)
        assertEquals(0.5, t.temperature)
        assertEquals("tr", t.apiKey)
    }

    @Test
    fun missingKeysAreReported() {
        val issues = Validation.issues(Settings(translation = TranslationSettings(pairs = listOf(TranslationPairSetting(to = "en")))), ApiKeys())
        val paths = issues.map { it.path }
        assertEquals(
            listOf("transcription.elevenlabs.apiKey", "polish.apiKey", "command.apiKey", "translation.apiKey"),
            paths,
        )
        assertTrue(issues.first().blocking)
        assertTrue(Validation.issues(Settings(), ApiKeys(elevenlabs = "e", openai = "o")).isEmpty())
    }

    @Test
    fun localEndpointsNeedNoKey() {
        for (url in listOf(
            "http://localhost:11434/v1", "http://127.0.0.1:8080", "http://192.168.1.20:11434/v1",
            "http://10.0.0.5/v1", "http://172.20.1.1/v1", "http://ollama.local:11434/v1",
        )) assertTrue(Validation.isLocal(url), url)
        for (url in listOf("https://api.openai.com/v1", "http://172.32.0.1/v1", "https://example.com")) {
            assertFalse(Validation.isLocal(url), url)
        }
        val s = Settings(polish = PolishSettings(baseUrl = "http://192.168.1.20:11434/v1"), command = CommandSettings(enabled = false))
        assertTrue(Validation.issues(s, ApiKeys(elevenlabs = "e")).isEmpty())
    }
}

class DictionaryTest {
    @Test
    fun normalizedAndDeduplicated() {
        val d = Dictionary.normalize(
            listOf(
                DictionaryEntry(" Kubernetes "), DictionaryEntry("kubernetes"), DictionaryEntry(" "),
                DictionaryEntry("pnpm", listOf(" p n p m ", "")),
            ),
        )
        assertEquals(listOf(DictionaryEntry("Kubernetes"), DictionaryEntry("pnpm", listOf("p n p m"))), d)
    }

    @Test
    fun termValidation() {
        assertEquals("Visual Studio Code", Dictionary.validateTerm("  Visual   Studio\tCode "))
        assertEquals("nothing selected", assertFailsWith<IllegalArgumentException> { Dictionary.validateTerm("  ") }.message)
        assertEquals(
            "the selection spans several lines",
            assertFailsWith<IllegalArgumentException> { Dictionary.validateTerm("a\nb") }.message,
        )
        assertFailsWith<IllegalArgumentException> { Dictionary.validateTerm("one two three four five six") }
        assertFailsWith<IllegalArgumentException> { Dictionary.validateTerm("x".repeat(50)) }
        assertFailsWith<IllegalArgumentException> { Dictionary.validateTerm("a<b") }
        assertTrue(Dictionary.contains(listOf(DictionaryEntry("TypeScript")), " typescript "))
    }

    @Test
    fun keytermRules() {
        val (ok, skipped) = Keyterms.split(
            listOf(
                DictionaryEntry("Kubernetes"), DictionaryEntry("one two three four five six"),
                DictionaryEntry("a{b}"), DictionaryEntry("x".repeat(49)), DictionaryEntry("y".repeat(50)),
            ),
        )
        assertEquals(listOf("Kubernetes", "x".repeat(49)), ok)
        assertEquals(3, skipped.size)
    }
}
