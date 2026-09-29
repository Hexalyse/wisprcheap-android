package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.CommandSettings
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.LlmOverride
import io.github.hexalyse.wisprcheap.core.settings.PriceOverride
import io.github.hexalyse.wisprcheap.core.settings.PricingSettings
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.settings.TranslationSettings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfileTest {
    private val dk = DataKey.generate()

    private val rich = Settings(
        dictionary = listOf(DictionaryEntry("Kubernetes"), DictionaryEntry(" pnpm ", listOf("p n p m")), DictionaryEntry("kubernetes")),
        command = CommandSettings(llm = LlmOverride(model = "gpt-6-sol", inheritReasoningEffort = false, reasoningEffort = "low", inheritTemperature = false)),
        translation = TranslationSettings(pairs = listOf(TranslationPairSetting("fra", "EN"), TranslationPairSetting(null, "de")), active = "fr>en"),
        pricing = PricingSettings(mapOf("my-llm" to PriceOverride(inputPerM = 1.0, outputPerM = 2.0))),
    )
    private val keys = ApiKeys(elevenlabs = "el", openai = "sk", polish = "sk", command = "gsk", translation = "sk")

    @Test
    fun profileShape() {
        val p = SyncProfile.profile(rich, keys, dk)
        assertEquals(28, p.values.count { it.kind == SyncProfile.SETTING })
        assertEquals(SyncProfile.INHERIT, p["setting/command.baseUrl"]!!.value)
        assertEquals(JsonPrimitive("low"), p["setting/command.reasoningEffort"]!!.value)
        assertEquals(JsonNull, p["setting/command.temperature"]!!.value)
        assertEquals(SyncProfile.INHERIT, p["setting/translation.temperature"]!!.value)
        val secrets = p.values.filter { it.kind == SyncProfile.SECRET }.associate { it.id to (it.value as JsonPrimitive).content }
        assertEquals(mapOf("elevenlabs" to "el", "openai" to "sk", "polish" to "", "command" to "gsk", "translation" to ""), secrets)
        val dict = p.values.filter { it.kind == SyncProfile.DICT }
        assertEquals(2, dict.size)
        assertEquals("""{"soundsLike":["p n p m"],"term":"pnpm"}""", canonicalJson(dict[1].value))
        assertEquals(dk.blindId("dict", "pnpm"), dict[1].id)
        val pairs = p.values.filter { it.kind == SyncProfile.PAIR }.map { canonicalJson(it.value) }
        assertEquals(listOf("""{"from":"fr","to":"en"}""", """{"from":null,"to":"de"}"""), pairs)
        assertEquals("""{"inputPerM":1.0,"model":"my-llm","outputPerM":2.0}""", canonicalJson(p.values.single { it.kind == SyncProfile.PRICE }.value))
    }

    @Test
    fun applyingAProfileReproducesIt() {
        val source = SyncProfile.profile(rich, keys, dk)
        val incoming = source.values.map { Incoming(it.kind, it.id, it.value) }
        val result = SyncProfile.apply(Settings(), ApiKeys(), dk, incoming)
        assertTrue(result.ignored.isEmpty(), result.ignored.toString())
        val again = SyncProfile.profile(result.settings, result.keys, dk)
        assertEquals(source.mapValues { canonicalJson(it.value.value) }, again.mapValues { canonicalJson(it.value.value) })
        // Per-device options aren't touched.
        assertEquals(null, result.settings.translation.active)
    }

    @Test
    fun deletionsAndInvalidValues() {
        val base = SyncProfile.apply(rich, keys, dk, emptyList())
        val del = SyncProfile.apply(
            base.settings, base.keys, dk,
            listOf(
                Incoming("dict", dk.blindId("dict", "kubernetes"), null),
                Incoming("pair", dk.blindId("pair", "fr>en"), null),
                Incoming("price", dk.blindId("price", "my-llm"), null),
                Incoming("setting", "polish.temperature", JsonPrimitive(5.0)),
                Incoming("setting", "transcription.provider", JsonPrimitive("nope")),
            ),
        )
        assertEquals(listOf("pnpm"), del.settings.dictionary.map { it.term.trim() })
        assertEquals(listOf(TranslationPairSetting(null, "de")), del.settings.translation.pairs)
        assertTrue(del.settings.pricing.overrides.isEmpty())
        assertEquals(2, del.ignored.size)
        assertEquals(null, del.settings.polish.temperature)
    }
}
