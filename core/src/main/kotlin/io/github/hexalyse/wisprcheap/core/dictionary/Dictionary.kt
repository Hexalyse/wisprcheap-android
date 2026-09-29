package io.github.hexalyse.wisprcheap.core.dictionary

import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.text.Words

/** Dictionary rules (desktop `dictionary.rs` and `config.rs`). */
object Dictionary {
    private val FORBIDDEN = charArrayOf('<', '>', '{', '}', '[', ']', '\\')

    /** Trims terms and hints, drops empty terms and case-insensitive duplicates (first one wins). */
    fun normalize(entries: List<DictionaryEntry>): List<DictionaryEntry> {
        val seen = HashSet<String>()
        return entries.mapNotNull { e ->
            val term = e.term.trim()
            if (term.isEmpty() || !seen.add(term.lowercase())) return@mapNotNull null
            DictionaryEntry(term, e.soundsLike.map { it.trim() }.filter { it.isNotEmpty() })
        }
    }

    /**
     * Validates a term typed or selected by the user, with the Scribe keyterm limits so every term is also
     * usable for transcription. Returns the cleaned term (whitespace collapsed) or throws with the reason.
     */
    fun validateTerm(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "nothing selected" }
        require('\r' !in trimmed && '\n' !in trimmed) { "the selection spans several lines" }
        val term = Words.split(trimmed).joinToString(" ")
        require(term.length < 50) { "\"${term.take(30)}...\" is too long (50 characters max)" }
        require(term.split(' ').size <= 5) { "\"$term\" has more than 5 words" }
        require(term.none { it in FORBIDDEN }) { "\"$term\" contains one of < > { } [ ] \\" }
        return term
    }

    /** True when [term] is already in the dictionary (case-insensitive). */
    fun contains(entries: List<DictionaryEntry>, term: String): Boolean =
        entries.any { it.term.equals(term.trim(), ignoreCase = true) }

    /** Whether a term can be sent as an ElevenLabs Scribe keyterm. */
    fun isValidKeyterm(term: String): Boolean =
        term.length < 50 && Words.count(term) <= 5 && term.none { it in FORBIDDEN }
}
