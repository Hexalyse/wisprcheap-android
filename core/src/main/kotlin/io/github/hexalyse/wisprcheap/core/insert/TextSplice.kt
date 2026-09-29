package io.github.hexalyse.wisprcheap.core.insert

/** Text manipulation for insertion (PLAN.md 8.3). */
object TextSplice {
    /** No space is added before text starting with one of these, nor before them when they follow the cursor. */
    private const val CLOSING = ",.;:!?)]}…%"

    /** No space is added after these (the cursor follows an opening bracket or quote). */
    private const val OPENING = "([{«“‘/"

    /**
     * Dictated text with the spaces it needs around the cursor.
     * [before]/[after]: the characters around the cursor (null = start/end of the text);
     * [contextKnown] = false when the surrounding text couldn't be read: then only the desktop rule applies
     * (a trailing space if [trailingSpace]).
     */
    fun forDictation(
        text: String,
        before: Char?,
        after: Char?,
        contextKnown: Boolean,
        smartSpacing: Boolean,
        trailingSpace: Boolean,
    ): String {
        if (text.isEmpty()) return text
        if (!smartSpacing || !contextKnown) return if (trailingSpace) "$text " else text
        val first = text.first()
        val lead = before != null && !before.isWhitespace() && before !in OPENING &&
            !first.isWhitespace() && first !in CLOSING
        val trail = trailingSpace && !text.last().isWhitespace() &&
            !(after != null && (after.isWhitespace() || after in CLOSING))
        return (if (lead) " " else "") + text + (if (trail) " " else "")
    }

    /** Replaces [start, end) of [current] with [insert]; returns the new text and the cursor after the insert. */
    fun replace(current: String, start: Int, end: Int, insert: String): Pair<String, Int> {
        val a = minOf(start, end).coerceIn(0, current.length)
        val b = maxOf(start, end).coerceIn(a, current.length)
        return (current.substring(0, a) + insert + current.substring(b)) to (a + insert.length)
    }
}
