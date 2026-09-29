package io.github.hexalyse.wisprcheap.core.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmTest {
    @Test
    fun durationOfOneSecond() {
        assertEquals(1000.0, Pcm.durationMs(16_000))
    }

    @Test
    fun silenceIsMinusInfinity() {
        assertEquals(Double.NEGATIVE_INFINITY, Pcm.loudestWindowDb(ShortArray(3200)))
        assertEquals(Double.NEGATIVE_INFINITY, Pcm.loudestWindowDb(ShortArray(0)))
        assertTrue(Pcm.isAllZero(ShortArray(10)))
    }

    @Test
    fun fullScaleSquareIsAboutZeroDb() {
        val pcm = ShortArray(1600) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertTrue(abs(Pcm.loudestWindowDb(pcm)) < 0.01)
        assertFalse(Pcm.isAllZero(pcm))
    }

    @Test
    fun loudestWindowWins() {
        // 100 ms of silence, then 100 ms at half scale: the loudest window is ~-6 dBFS.
        val pcm = ShortArray(3200) { if (it < 1600) 0 else 16384 }
        assertTrue(abs(Pcm.loudestWindowDb(pcm) - (-6.02)) < 0.01)
    }

    @Test
    fun countLimitsTheSamplesUsed() {
        val pcm = ShortArray(3200) { if (it < 1600) 0 else 16384 }
        assertEquals(Double.NEGATIVE_INFINITY, Pcm.loudestWindowDb(pcm, 1600))
        assertTrue(Pcm.isAllZero(pcm, 1600))
    }

    @Test
    fun wavHeader() {
        val wav = Pcm.encodeWav(shortArrayOf(1, -1, 256))
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(6, wav[40].toInt()) // data size, little-endian
        // Samples: 1 -> 01 00, -1 -> FF FF, 256 -> 00 01
        assertEquals(listOf(1, 0, 0xFF, 0xFF, 0, 1), (44 until 50).map { wav[it].toInt() and 0xFF })
    }
}
