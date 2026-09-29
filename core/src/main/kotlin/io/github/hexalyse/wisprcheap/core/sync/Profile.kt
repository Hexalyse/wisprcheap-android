package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.LlmOverride
import io.github.hexalyse.wisprcheap.core.settings.PriceOverride
import io.github.hexalyse.wisprcheap.core.settings.Provider
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.translate.Languages
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One record of the local profile. [order]: position, so list items are sent in order. */
data class LocalRecord(val kind: String, val id: String, val value: JsonElement, val order: Int)

/** A remote record to apply locally ([value] null = deleted). */
data class Incoming(val kind: String, val id: String, val value: JsonElement?) {
    val key: String get() = recordKey(kind, id)
}

data class ApplyResult(
    val settings: Settings,
    val keys: ApiKeys,
    /** Local changes made, per kind. */
    val changed: Map<String, Int>,
    /** Records ignored because their value is invalid here. */
    val ignored: List<String>,
)

/**
 * The synced profile (desktop `sync/SPEC.md` section 5) ↔ [Settings] and [ApiKeys]. Per-device options
 * (bubble, recording, output, history, notifications, command on/off, active pair) are never synced.
 */
object SyncProfile {
    const val SETTING = "setting"
    const val SECRET = "secret"
    const val DICT = "dict"
    const val PAIR = "pair"
    const val PRICE = "price"
    const val HISTORY = "history"

    /** Same order as the desktop's `profile::SETTINGS`. */
    val SETTING_IDS = listOf(
        "transcription.provider", "transcription.language", "transcription.timeoutMs",
        "transcription.elevenlabs.model", "transcription.elevenlabs.baseUrl",
        "transcription.elevenlabs.keyterms", "transcription.elevenlabs.noVerbatim",
        "transcription.openai.model", "transcription.openai.baseUrl", "transcription.openai.prompt",
        "polish.enabled", "polish.baseUrl", "polish.model", "polish.reasoningEffort", "polish.temperature",
        "polish.timeoutMs", "polish.minWords", "polish.instructions",
        "command.baseUrl", "command.model", "command.reasoningEffort", "command.temperature", "command.timeoutMs",
        "translation.baseUrl", "translation.model", "translation.reasoningEffort", "translation.temperature",
        "translation.timeoutMs",
    )

    val SECRETS = listOf("elevenlabs", "openai", "polish", "command", "translation")

    val INHERIT: JsonObject = buildJsonObject { put("inherit", true) }

    fun isInherit(v: JsonElement?) = (v as? JsonObject)?.get("inherit")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true

    fun dictKey(term: String) = term.trim().lowercase()

    fun pairKey(from: String?, to: String) = "${from ?: "auto"}>$to"

    fun priceKey(model: String) = model.trim()

    /** [current] with the synced fields of [synced]; per-device options of [current] are kept. */
    fun mergeSynced(current: Settings, synced: Settings): Settings = current.copy(
        transcription = synced.transcription,
        polish = synced.polish,
        dictionary = synced.dictionary,
        command = current.command.copy(llm = synced.command.llm, timeoutMs = synced.command.timeoutMs),
        translation = current.translation.copy(
            pairs = synced.translation.pairs,
            llm = synced.translation.llm,
            timeoutMs = synced.translation.timeoutMs,
        ),
        pricing = synced.pricing,
    )

    private fun str(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    private fun num(v: Double?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull

    private fun inheritOr(v: String?): JsonElement = v?.let(::JsonPrimitive) ?: INHERIT

    fun settingValue(s: Settings, id: String): JsonElement? {
        val t = s.transcription
        val p = s.polish
        fun llm(o: LlmOverride, field: String): JsonElement = when (field) {
            "baseUrl" -> inheritOr(o.baseUrl)
            "model" -> inheritOr(o.model)
            "reasoningEffort" -> if (o.inheritReasoningEffort) INHERIT else str(o.reasoningEffort)
            else -> if (o.inheritTemperature) INHERIT else num(o.temperature)
        }
        return when (id) {
            "transcription.provider" -> JsonPrimitive(t.provider.id)
            "transcription.language" -> JsonPrimitive(t.language)
            "transcription.timeoutMs" -> JsonPrimitive(t.timeoutMs)
            "transcription.elevenlabs.model" -> JsonPrimitive(t.elevenlabs.model)
            "transcription.elevenlabs.baseUrl" -> JsonPrimitive(t.elevenlabs.baseUrl)
            "transcription.elevenlabs.keyterms" -> JsonPrimitive(t.elevenlabs.keyterms)
            "transcription.elevenlabs.noVerbatim" -> JsonPrimitive(t.elevenlabs.noVerbatim)
            "transcription.openai.model" -> JsonPrimitive(t.openai.model)
            "transcription.openai.baseUrl" -> JsonPrimitive(t.openai.baseUrl)
            "transcription.openai.prompt" -> JsonPrimitive(t.openai.prompt)
            "polish.enabled" -> JsonPrimitive(p.enabled)
            "polish.baseUrl" -> JsonPrimitive(p.baseUrl)
            "polish.model" -> JsonPrimitive(p.model)
            "polish.reasoningEffort" -> str(p.reasoningEffort)
            "polish.temperature" -> num(p.temperature)
            "polish.timeoutMs" -> JsonPrimitive(p.timeoutMs)
            "polish.minWords" -> JsonPrimitive(p.minWords)
            "polish.instructions" -> JsonPrimitive(p.instructions)
            "command.timeoutMs" -> JsonPrimitive(s.command.timeoutMs)
            "translation.timeoutMs" -> JsonPrimitive(s.translation.timeoutMs)
            else -> when {
                id.startsWith("command.") -> llm(s.command.llm, id.removePrefix("command."))
                id.startsWith("translation.") -> llm(s.translation.llm, id.removePrefix("translation."))
                else -> null
            }
        }
    }

    /** Synced keys; `""` = the fallback (cleanup → OpenAI key, command/translation → cleanup key). */
    fun secretValues(k: ApiKeys): Map<String, String> {
        val cleanup = k.polish.ifEmpty { k.openai }
        fun secondary(v: String) = if (v.isEmpty() || v == cleanup) "" else v
        return linkedMapOf(
            "elevenlabs" to k.elevenlabs,
            "openai" to k.openai,
            "polish" to if (k.polish == k.openai) "" else k.polish,
            "command" to secondary(k.command),
            "translation" to secondary(k.translation),
        )
    }

    fun dictValue(term: String, soundsLike: List<String>): JsonElement = buildJsonObject {
        put("term", term)
        put("soundsLike", JsonArray(soundsLike.map(::JsonPrimitive)))
    }

    fun pairValue(from: String?, to: String): JsonElement = buildJsonObject {
        put("from", str(from))
        put("to", to)
    }

    fun priceValue(model: String, o: PriceOverride): JsonElement = buildJsonObject {
        put("model", model)
        o.perMinute?.let { put("perMinute", it) }
        o.inputPerM?.let { put("inputPerM", it) }
        o.outputPerM?.let { put("outputPerM", it) }
    }

    /** `kind/id` → record, in a stable order. */
    fun profile(s: Settings, k: ApiKeys, dk: DataKey): LinkedHashMap<String, LocalRecord> {
        val out = LinkedHashMap<String, LocalRecord>()
        fun put(kind: String, id: String, value: JsonElement) {
            out[recordKey(kind, id)] = LocalRecord(kind, id, value, out.size)
        }
        SETTING_IDS.forEach { id -> settingValue(s, id)?.let { put(SETTING, id, it) } }
        secretValues(k).forEach { (name, v) -> put(SECRET, name, JsonPrimitive(v)) }
        val seen = HashSet<String>()
        for (e in s.dictionary) {
            val term = e.term.trim()
            val key = dictKey(term)
            if (key.isEmpty() || !seen.add(key)) continue
            put(DICT, dk.blindId(DICT, key), dictValue(term, e.soundsLike.map(String::trim).filter(String::isNotEmpty)))
        }
        for (pair in TranslationPairs.build(s.translation.pairs).first) {
            put(PAIR, dk.blindId(PAIR, pairKey(pair.from, pair.to)), pairValue(pair.from, pair.to))
        }
        for ((model, o) in s.pricing.overrides) {
            val m = priceKey(model)
            if (m.isEmpty()) continue
            put(PRICE, dk.blindId(PRICE, m), priceValue(m, o))
        }
        return out
    }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** A string, or null for JSON null; failure for anything else. */
    private fun nullableString(v: JsonElement): Result<String?> = when {
        v is JsonNull -> Result.success(null)
        v.string() != null -> Result.success(v.string())
        else -> Result.failure(IllegalArgumentException("expected a string or null"))
    }

    private fun nullableTemperature(v: JsonElement): Result<Double?> = when {
        v is JsonNull -> Result.success(null)
        v.double()?.let { it in 0.0..2.0 } == true -> Result.success(v.double())
        else -> Result.failure(IllegalArgumentException("expected a temperature between 0 and 2"))
    }

    private fun positive(v: JsonElement): Long = v.long()?.takeIf { it > 0 } ?: throw IllegalArgumentException("expected a positive number")

    private fun nonEmpty(v: JsonElement): String = v.string()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("expected a non-empty string")

    private fun applyLlm(o: LlmOverride, field: String, v: JsonElement): LlmOverride = when (field) {
        "baseUrl" -> o.copy(baseUrl = if (isInherit(v)) null else nonEmpty(v))
        "model" -> o.copy(model = if (isInherit(v)) null else nonEmpty(v))
        "reasoningEffort" -> if (isInherit(v)) o.copy(inheritReasoningEffort = true, reasoningEffort = null)
        else o.copy(inheritReasoningEffort = false, reasoningEffort = nullableString(v).getOrThrow())
        "temperature" -> if (isInherit(v)) o.copy(inheritTemperature = true, temperature = null)
        else o.copy(inheritTemperature = false, temperature = nullableTemperature(v).getOrThrow())
        else -> o
    }

    /** [s] with one remote setting applied (throws IllegalArgumentException on an invalid value). */
    fun applySetting(s: Settings, id: String, v: JsonElement): Settings {
        val t = s.transcription
        val p = s.polish
        fun bool() = v.bool() ?: throw IllegalArgumentException("expected true or false")
        return when (id) {
            "transcription.provider" -> s.copy(
                transcription = t.copy(
                    provider = Provider.entries.firstOrNull { it.id == v.string() } ?: throw IllegalArgumentException("unknown provider"),
                ),
            )
            "transcription.language" -> s.copy(transcription = t.copy(language = nonEmpty(v)))
            "transcription.timeoutMs" -> s.copy(transcription = t.copy(timeoutMs = positive(v)))
            "transcription.elevenlabs.model" -> s.copy(transcription = t.copy(elevenlabs = t.elevenlabs.copy(model = nonEmpty(v))))
            "transcription.elevenlabs.baseUrl" -> s.copy(transcription = t.copy(elevenlabs = t.elevenlabs.copy(baseUrl = nonEmpty(v))))
            "transcription.elevenlabs.keyterms" -> s.copy(transcription = t.copy(elevenlabs = t.elevenlabs.copy(keyterms = bool())))
            "transcription.elevenlabs.noVerbatim" -> s.copy(transcription = t.copy(elevenlabs = t.elevenlabs.copy(noVerbatim = bool())))
            "transcription.openai.model" -> s.copy(transcription = t.copy(openai = t.openai.copy(model = nonEmpty(v))))
            "transcription.openai.baseUrl" -> s.copy(transcription = t.copy(openai = t.openai.copy(baseUrl = nonEmpty(v))))
            "transcription.openai.prompt" -> s.copy(
                transcription = t.copy(openai = t.openai.copy(prompt = v.string() ?: throw IllegalArgumentException("expected a string"))),
            )
            "polish.enabled" -> s.copy(polish = p.copy(enabled = bool()))
            "polish.baseUrl" -> s.copy(polish = p.copy(baseUrl = nonEmpty(v)))
            "polish.model" -> s.copy(polish = p.copy(model = nonEmpty(v)))
            "polish.reasoningEffort" -> s.copy(polish = p.copy(reasoningEffort = nullableString(v).getOrThrow()))
            "polish.temperature" -> s.copy(polish = p.copy(temperature = nullableTemperature(v).getOrThrow()))
            "polish.timeoutMs" -> s.copy(polish = p.copy(timeoutMs = positive(v)))
            "polish.minWords" -> s.copy(
                polish = p.copy(minWords = v.long()?.takeIf { it in 0..100_000 }?.toInt() ?: throw IllegalArgumentException("expected a number")),
            )
            "polish.instructions" -> s.copy(polish = p.copy(instructions = nonEmpty(v).trim()))
            "command.timeoutMs" -> s.copy(command = s.command.copy(timeoutMs = positive(v)))
            "translation.timeoutMs" -> s.copy(translation = s.translation.copy(timeoutMs = positive(v)))
            else -> when {
                id.startsWith("command.") -> s.copy(command = s.command.copy(llm = applyLlm(s.command.llm, id.removePrefix("command."), v)))
                id.startsWith("translation.") ->
                    s.copy(translation = s.translation.copy(llm = applyLlm(s.translation.llm, id.removePrefix("translation."), v)))
                else -> s // a setting from a newer app version
            }
        }
    }

    private fun canonicalPair(from: String?, to: String): Pair<String?, String>? {
        val t = Languages.canonical(to) ?: return null
        val f = from?.trim()?.takeUnless { it.isEmpty() || it.equals("auto", true) || it.equals("any", true) }
        val fc = if (f == null) null else Languages.canonical(f) ?: return null
        return fc to t
    }

    /** Applies remote records to [s] / [k]. */
    fun apply(s0: Settings, k0: ApiKeys, dk: DataKey, incoming: List<Incoming>): ApplyResult {
        var s = s0
        var k = k0
        val changed = LinkedHashMap<String, Int>()
        val ignored = mutableListOf<String>()
        fun changed(kind: String) {
            changed[kind] = (changed[kind] ?: 0) + 1
        }
        for (inc in incoming) {
            val local = profile(s, k, dk)[inc.key]?.value
            val v = inc.value
            if (v != null && local != null && canonicalJson(local) == canonicalJson(v)) continue
            if (v == null && local == null) continue
            try {
                when (inc.kind) {
                    SETTING -> if (v != null && inc.id in SETTING_IDS) {
                        s = applySetting(s, inc.id, v)
                        changed(SETTING)
                    }
                    SECRET -> {
                        val value = v.string() ?: throw IllegalArgumentException("expected a string")
                        k = when (inc.id) {
                            "elevenlabs" -> k.copy(elevenlabs = value)
                            "openai" -> k.copy(openai = value)
                            "polish" -> k.copy(polish = value)
                            "command" -> k.copy(command = value)
                            "translation" -> k.copy(translation = value)
                            else -> throw IllegalArgumentException("unknown API key")
                        }
                        changed(SECRET)
                    }
                    DICT -> {
                        if (v == null) {
                            val kept = s.dictionary.filterNot { dk.blindId(DICT, dictKey(it.term)) == inc.id }
                            if (kept.size != s.dictionary.size) {
                                s = s.copy(dictionary = kept)
                                changed(DICT)
                            }
                        } else {
                            val o = v as? JsonObject ?: throw IllegalArgumentException("expected an object")
                            val term = o["term"].string()?.trim().orEmpty()
                            val key = dictKey(term)
                            if (key.isEmpty() || dk.blindId(DICT, key) != inc.id) throw IllegalArgumentException("inconsistent term")
                            val sounds = (o["soundsLike"] as? JsonArray)?.mapNotNull { it.string()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
                            val entry = DictionaryEntry(term, sounds)
                            val i = s.dictionary.indexOfFirst { dictKey(it.term) == key }
                            s = s.copy(dictionary = if (i >= 0) s.dictionary.toMutableList().also { it[i] = entry } else s.dictionary + entry)
                            changed(DICT)
                        }
                    }
                    PAIR -> {
                        val pairs = s.translation.pairs
                        fun idOf(p: TranslationPairSetting) = canonicalPair(p.from, p.to)?.let { (f, t) -> dk.blindId(PAIR, pairKey(f, t)) }
                        if (v == null) {
                            val kept = pairs.filterNot { idOf(it) == inc.id }
                            if (kept.size != pairs.size) {
                                s = s.copy(translation = s.translation.copy(pairs = kept))
                                changed(PAIR)
                            }
                        } else {
                            val o = v as? JsonObject ?: throw IllegalArgumentException("expected an object")
                            val (from, to) = canonicalPair(o["from"].string(), o["to"].string().orEmpty())
                                ?: throw IllegalArgumentException("invalid language code")
                            if (dk.blindId(PAIR, pairKey(from, to)) != inc.id) throw IllegalArgumentException("inconsistent pair")
                            if (pairs.none { idOf(it) == inc.id }) {
                                s = s.copy(translation = s.translation.copy(pairs = pairs + TranslationPairSetting(from, to)))
                                changed(PAIR)
                            }
                        }
                    }
                    PRICE -> {
                        val overrides = s.pricing.overrides
                        if (v == null) {
                            val kept = overrides.filterKeys { dk.blindId(PRICE, priceKey(it)) != inc.id }
                            if (kept.size != overrides.size) {
                                s = s.copy(pricing = s.pricing.copy(overrides = kept))
                                changed(PRICE)
                            }
                        } else {
                            val o = v as? JsonObject ?: throw IllegalArgumentException("expected an object")
                            val model = priceKey(o["model"].string().orEmpty())
                            if (model.isEmpty() || dk.blindId(PRICE, model) != inc.id) throw IllegalArgumentException("inconsistent model")
                            val price = PriceOverride(o["perMinute"].double(), o["inputPerM"].double(), o["outputPerM"].double())
                            val bad = listOfNotNull(price.perMinute, price.inputPerM, price.outputPerM).any { it < 0 || it.isNaN() || it.isInfinite() }
                            if (bad) throw IllegalArgumentException("invalid price")
                            val kept = overrides.filterKeys { priceKey(it) != model }
                            s = s.copy(pricing = s.pricing.copy(overrides = kept + (model to price)))
                            changed(PRICE)
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                ignored += "${inc.key}: ${e.message}"
            }
        }
        return ApplyResult(s, k, changed, ignored)
    }
}
