package io.github.hexalyse.wisprcheap.core.translate

import io.github.hexalyse.wisprcheap.core.pricing.Pricing
import io.github.hexalyse.wisprcheap.core.settings.PriceOverride
import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.settings.TranslationSettings
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagesTest {
    @Test
    fun canonicalizes() {
        assertEquals("fr", Languages.canonical("FR"))
        assertEquals("fr", Languages.canonical("fra"))
        assertEquals("de", Languages.canonical("ger"))
        assertEquals("en", Languages.canonical("en-US"))
        assertEquals("he", Languages.canonical("iw"))
        assertNull(Languages.canonical("not a language"))
        assertEquals("French", Languages.name("fr"))
        assertEquals("English", Languages.name("en"))
        assertEquals("Cantonese", Languages.name("yue"))
        assertEquals("xx", Languages.name("xx"))
        assertTrue(Languages.all.any { it.code == "fr" && it.name == "French" })
    }

    @Test
    fun pairs() {
        val (pairs, problems) = TranslationPairs.build(
            listOf(
                TranslationPairSetting("fra", "en-GB"),
                TranslationPairSetting(null, "en"),
                TranslationPairSetting("fr", "en"), // duplicate of the first
                TranslationPairSetting("auto", "de"),
                TranslationPairSetting("12", "en"),
            ),
        )
        assertEquals(listOf("fr>en", "auto>en", "auto>de"), pairs.map { it.id })
        assertEquals(listOf("French → English", "Any → English", "Any → German"), pairs.map { it.label })
        assertEquals("English", pairs[0].toName)
        assertEquals("fr", pairs[0].from)
        assertEquals(1, problems.size)
    }

    @Test
    fun activePair() {
        val s = TranslationSettings(pairs = listOf(TranslationPairSetting("fr", "en")), active = "fr>en")
        assertEquals("fr>en", TranslationPairs.active(s)?.id)
        assertNull(TranslationPairs.active(s.copy(active = "de>en")))
        assertNull(TranslationPairs.active(s.copy(active = null)))
    }
}

class PricingTest {
    private fun close(actual: Double?, expected: Double) =
        assertTrue(actual != null && abs(actual - expected) < 1e-12, "$actual != $expected")

    @Test
    fun scribeSurchargeAndMinimum() {
        val p = Pricing()
        close(p.transcriptionCost("scribe_v2", 60.0, 0), 0.22 / 60.0)
        close(p.transcriptionCost("scribe_v2", 60.0, 5), 0.27 / 60.0)
        close(p.transcriptionCost("scribe_v2", 5.0, 101), (20.0 / 60.0) * (0.27 / 60.0))
        close(p.transcriptionCost("gpt-4o-transcribe", 30.0, 5), 0.003)
        assertNull(p.llmCost("unknown-model", 100, 100))
        close(p.llmCost("gpt-6-luna", 1_000_000, 1_000_000), 0.6)
    }

    @Test
    fun overridesWin() {
        val p = Pricing(mapOf("my-model" to PriceOverride(inputPerM = 1.0, outputPerM = 2.0), "gpt-4o-transcribe" to PriceOverride(perMinute = 0.01)))
        close(p.llmCost("my-model", 1_000_000, 500_000), 2.0)
        close(p.transcriptionCost("gpt-4o-transcribe", 60.0, 0), 0.01)
    }
}
