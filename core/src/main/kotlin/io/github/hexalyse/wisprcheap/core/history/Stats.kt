package io.github.hexalyse.wisprcheap.core.history

import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** Totals for one month (local time), as shown on Home and in the desktop tray. */
data class MonthTotals(val month: String, val costUsd: Double = 0.0, val words: Int = 0, val dictations: Int = 0) {
    fun add(e: HistoryEntry): MonthTotals = copy(
        costUsd = costUsd + (e.costUsd.total ?: e.costUsd.transcription ?: 0.0),
        words = words + e.words,
        dictations = dictations + if (e.isSuccess) 1 else 0,
    )
}

/** One row of the stats table (desktop `wisprcheap stats`), over successful entries only. */
data class MonthStats(
    val month: String,
    val dictations: Int,
    val commands: Int,
    val words: Int,
    val audioMinutes: Double,
    val transcribeUsd: Double,
    val polishUsd: Double,
    val totalUsd: Double,
    /** Entries whose model had no known price (their cost counts as 0). */
    val unknownPrice: Int,
) {
    val per10kWords: Double? get() = if (words > 0) totalUsd / words * 10_000 else null
}

object Stats {
    /** "2026-09" in [zone], or null for an unparsable timestamp. */
    fun monthKey(ts: String, zone: ZoneId): String? {
        val d = Timestamps.parse(ts)?.atZone(zone) ?: return null
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    fun monthTotals(entries: List<HistoryEntry>, month: String, zone: ZoneId): MonthTotals =
        entries.filter { monthKey(it.ts, zone) == month }.fold(MonthTotals(month)) { acc, e -> acc.add(e) }

    /** Newest month first. */
    fun byMonth(entries: List<HistoryEntry>, zone: ZoneId): List<MonthStats> =
        entries.filter { it.isSuccess }
            .groupBy { monthKey(it.ts, zone) ?: "unknown" }
            .map { (month, list) ->
                MonthStats(
                    month = month,
                    dictations = list.count { !it.isCommand },
                    commands = list.count { it.isCommand },
                    words = list.sumOf { it.words },
                    audioMinutes = list.sumOf { it.durationSec } / 60.0,
                    transcribeUsd = list.sumOf { it.costUsd.transcription ?: 0.0 },
                    polishUsd = list.sumOf { it.costUsd.polish ?: 0.0 },
                    totalUsd = list.sumOf { it.costUsd.total ?: it.costUsd.transcription ?: 0.0 },
                    unknownPrice = list.count {
                        it.costUsd.transcription == null || (it.polish != null && it.polish.error == null && it.costUsd.polish == null)
                    },
                )
            }
            .sortedByDescending { it.month }

    /** "September" for "2026-09". */
    fun monthName(month: String, locale: Locale = Locale.ENGLISH): String {
        val m = month.substringAfter('-').toIntOrNull() ?: return month
        return java.time.Month.of(m).getDisplayName(TextStyle.FULL, locale)
    }
}

object Money {
    /** Home / tray: "~$0.12", or "<$0.01" for tiny non-zero amounts. */
    fun approx(usd: Double): String =
        if (usd > 0 && usd < 0.01) "<$0.01" else "~$" + "%.2f".format(Locale.ROOT, usd)

    /** Tables: "$0.0000" under $1, else "$0.00". */
    fun table(usd: Double): String =
        if (abs(usd) < 1) "$" + "%.4f".format(Locale.ROOT, usd) else "$" + "%.2f".format(Locale.ROOT, usd)

    /** Log lines: "~$0.00030". */
    fun log(usd: Double): String = "~$" + "%.5f".format(Locale.ROOT, usd)

    /** "1,234". */
    fun thousands(n: Int): String = "%,d".format(Locale.ENGLISH, n)
}
