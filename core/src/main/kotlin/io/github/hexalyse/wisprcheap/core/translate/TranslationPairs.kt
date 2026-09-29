package io.github.hexalyse.wisprcheap.core.translate

import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.settings.TranslationSettings

data class TranslationPair(
    /** Canonical code of the spoken language, or null to auto-detect. */
    val from: String?,
    val to: String,
    /** English name of the target language, used in the prompt (e.g. "English"). */
    val toName: String,
    /** Stable id used to remember the choice: "fr>en" or "auto>en". */
    val id: String,
    /** "French → English" or "Any → English". */
    val label: String,
)

object TranslationPairs {
    /** Valid pairs (canonicalised, duplicates dropped) and the problems found. */
    fun build(settings: List<TranslationPairSetting>): Pair<List<TranslationPair>, List<String>> {
        val pairs = mutableListOf<TranslationPair>()
        val problems = mutableListOf<String>()
        for ((i, p) in settings.withIndex()) {
            val fromRaw = p.from?.trim()?.takeUnless { it.isEmpty() || it.equals("auto", true) || it.equals("any", true) }
            val from = fromRaw?.let { Languages.canonical(it) }
            val to = Languages.canonical(p.to)
            if (fromRaw != null && from == null) {
                problems += "translation.pairs[$i].from: \"${p.from}\" is not a language code"
                continue
            }
            if (to == null) {
                problems += "translation.pairs[$i].to: \"${p.to}\" is not a language code"
                continue
            }
            val id = "${from ?: "auto"}>$to"
            if (pairs.any { it.id == id }) continue
            val toName = Languages.name(to)
            val label = "${from?.let { Languages.name(it) } ?: "Any"} → $toName"
            pairs += TranslationPair(from, to, toName, id, label)
        }
        return pairs to problems
    }

    /** The active pair, or null when translation is off or the selected pair no longer exists. */
    fun active(settings: TranslationSettings): TranslationPair? {
        val id = settings.active ?: return null
        return build(settings.pairs).first.firstOrNull { it.id == id }
    }
}
