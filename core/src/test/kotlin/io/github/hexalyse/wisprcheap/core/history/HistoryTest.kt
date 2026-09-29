package io.github.hexalyse.wisprcheap.core.history

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val base = HistoryEntry(
        ts = "2026-09-29T12:34:56.789Z",
        durationSec = 3.2,
        transcription = TranscriptionInfo("elevenlabs", "scribe_v2", 812, 2),
        polish = PolishInfo("gpt-6-luna", 640, 350, 20),
        raw = "hello world",
        text = "Hello world.",
        words = 2,
        delivered = Delivered.INSERTED,
        costUsd = Costs(0.0002347, 0.000045, 0.0002797),
        app = "com.whatsapp",
        insertMethod = "inputConnection",
    )

    @Test
    fun encodesInTheDesktopOrder() {
        val json = HistoryJson.encodeLine(base)
        assertEquals(
            """{"ts":"2026-09-29T12:34:56.789Z","durationSec":3.2,"transcription":{"provider":"elevenlabs","model":"scribe_v2","ms":812,"keyterms":2},""" +
                """"polish":{"model":"gpt-6-luna","ms":640,"inputTokens":350,"outputTokens":20},"raw":"hello world","text":"Hello world.",""" +
                """"words":2,"delivered":"inserted","costUsd":{"transcription":2.347E-4,"polish":4.5E-5,"total":2.797E-4},""" +
                """"app":"com.whatsapp","insertMethod":"inputConnection"}""",
            json,
        )
    }

    @Test
    fun selectionIsOnlyWrittenForCommands() {
        assertFalse(HistoryJson.encode(base).containsKey("selection"))
        val cmd = base.copy(mode = HistoryEntry.MODE_COMMAND, selection = null, polish = null)
        val o = HistoryJson.encode(cmd)
        assertTrue(o.containsKey("selection"))
        assertEquals("null", o["selection"].toString())
        assertEquals("null", o["polish"].toString())
    }

    @Test
    fun decodeRoundTrip() {
        val e = base.copy(retry = true, translation = "fr>en", polishSkipped = 3, error = "x", audioFile = "/a.wav")
        assertEquals(e, HistoryJson.decode(Json.parseToJsonElement(HistoryJson.encodeLine(e)).jsonObject))
    }

    @Test
    fun timestampsHaveMilliseconds() {
        assertEquals("2026-09-29T12:00:00.000Z", Timestamps.iso(Instant.parse("2026-09-29T12:00:00Z")))
        assertEquals("2026-09-29T12:00:00.120Z", Timestamps.iso(Instant.parse("2026-09-29T12:00:00.12Z")))
    }

    @Test
    fun monthTotalsAndStats() {
        val zone = ZoneId.of("Europe/Paris")
        val entries = listOf(
            base,
            base.copy(ts = "2026-09-30T23:30:00.000Z", words = 10, costUsd = Costs(0.001, null, 0.001)), // 1 Oct in Paris
            base.copy(error = "boom", words = 0, costUsd = Costs(null, null, null)),
            base.copy(words = 0, text = "", costUsd = Costs(0.0001, null, null)), // empty transcript
            base.copy(mode = HistoryEntry.MODE_COMMAND, words = 4),
        )
        val sept = Stats.monthTotals(entries, "2026-09", zone)
        assertEquals(6, sept.words)
        assertEquals(2, sept.dictations) // base + command; failed and empty don't count
        assertEquals(0.0002797 * 2 + 0.0001, sept.costUsd, 1e-12)
        val stats = Stats.byMonth(entries, zone)
        assertEquals(listOf("2026-10", "2026-09"), stats.map { it.month })
        assertEquals(1, stats[1].dictations)
        assertEquals(1, stats[1].commands)
        assertEquals(10, stats[0].words)
        assertEquals(0.001 / 10 * 10_000, stats[0].per10kWords!!, 1e-12)
        assertEquals("September", Stats.monthName("2026-09"))
    }

    @Test
    fun moneyFormats() {
        assertEquals("<$0.01", Money.approx(0.004))
        assertEquals("~$0.00", Money.approx(0.0))
        assertEquals("~$1.23", Money.approx(1.234))
        assertEquals("$0.0012", Money.table(0.00123))
        assertEquals("$12.35", Money.table(12.345))
        assertEquals("~$0.00030", Money.log(0.0003))
        assertEquals("1,234", Money.thousands(1234))
        assertNull(Stats.monthKey("garbage", ZoneId.of("UTC")))
    }
}
