package io.github.hexalyse.wisprcheap.core.sync

/**
 * Hybrid logical clock (desktop `sync/SPEC.md` section 4): `<ms, 13 digits>-<counter, 4 hex>-<device id>`.
 * String order equals time order. Device ids are base64url, so they may contain `-` and `_`.
 */
data class Hlc(val ms: Long, val counter: Int, val node: String) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int =
        compareValuesBy(this, other, { it.ms }, { it.counter }, { it.node })

    override fun toString(): String = String.format(java.util.Locale.ROOT, "%013d-%04x-%s", ms, counter, node)

    companion object {
        private val NODE = Regex("[A-Za-z0-9_-]+")

        fun parse(text: String): Hlc? {
            val parts = text.split('-', limit = 3)
            if (parts.size != 3) return null
            val (ms, counter, node) = parts
            if (ms.length != 13 || !ms.all(Char::isDigit) || counter.length != 4 || !NODE.matches(node)) return null
            return Hlc(ms.toLongOrNull() ?: return null, counter.toIntOrNull(16) ?: return null, node)
        }
    }
}

/** A device's clock. Persist [last] and restore it with [observe] across restarts. */
class HlcClock(private val node: String) {
    private var ms = 0L
    private var counter = 0

    /** A new timestamp, greater than every timestamp issued or observed so far. */
    fun now(wallMs: Long = System.currentTimeMillis()): Hlc {
        when {
            wallMs > ms -> {
                ms = wallMs
                counter = 0
            }
            counter == 0xFFFF -> {
                ms += 1
                counter = 0
            }
            else -> counter += 1
        }
        return Hlc(ms, counter, node)
    }

    fun observe(other: Hlc) {
        if (other.ms > ms || (other.ms == ms && other.counter > counter)) {
            ms = other.ms
            counter = other.counter
        }
    }

    fun last() = Hlc(ms, counter, node)
}

/** `a >= b` by HLC (plain string order when one doesn't parse). */
fun hlcGe(a: String, b: String): Boolean {
    val x = Hlc.parse(a)
    val y = Hlc.parse(b)
    return if (x != null && y != null) x >= y else a >= b
}
