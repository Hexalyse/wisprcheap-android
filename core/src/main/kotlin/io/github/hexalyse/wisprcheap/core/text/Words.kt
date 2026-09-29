package io.github.hexalyse.wisprcheap.core.text

/** Whitespace word handling equivalent to Rust's `split_whitespace` (Unicode whitespace). */
object Words {
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var start = -1
        for (i in text.indices) {
            if (text[i].isWhitespace()) {
                if (start >= 0) {
                    out += text.substring(start, i)
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
        if (start >= 0) out += text.substring(start)
        return out
    }

    fun count(text: String): Int {
        var n = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                n++
            }
        }
        return n
    }
}
