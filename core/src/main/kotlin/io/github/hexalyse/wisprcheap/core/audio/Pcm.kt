package io.github.hexalyse.wisprcheap.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * PCM helpers for 16 kHz mono signed 16-bit audio (the format sent to the speech-to-text APIs).
 * Port of the desktop's `src/audio.rs`.
 */
object Pcm {
    const val SAMPLE_RATE = 16_000

    fun durationMs(samples: Int): Double = samples.toDouble() / SAMPLE_RATE * 1000.0

    /** Little-endian bytes of the first [count] samples. */
    fun toBytes(pcm: ShortArray, count: Int = pcm.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = pcm[i].toInt()
            out[2 * i] = (s and 0xFF).toByte()
            out[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Wrap the first [count] samples in a WAV container (PCM, mono, 16 kHz, 16-bit). */
    fun encodeWav(pcm: ShortArray, count: Int = pcm.size): ByteArray {
        val dataSize = count * 2
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun u32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun u16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        u32(36 + dataSize)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        u32(16) // fmt chunk size
        u16(1) // PCM
        u16(1) // mono
        u32(SAMPLE_RATE)
        u32(SAMPLE_RATE * 2) // byte rate
        u16(2) // block align
        u16(16) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        u32(dataSize)
        out.write(toBytes(pcm, count))
        return out.toByteArray()
    }

    /**
     * RMS level (dBFS) of the loudest non-overlapping 100 ms window of the first [count] samples.
     * Used to skip recordings with no speech at all. All zeros (or no samples) gives -Infinity.
     */
    fun loudestWindowDb(pcm: ShortArray, count: Int = pcm.size): Double {
        val window = max(SAMPLE_RATE * 100 / 1000, 1)
        var loudest = 0.0
        var start = 0
        while (start < count) {
            val end = minOf(start + window, count)
            var sum = 0.0
            for (i in start until end) {
                val v = pcm[i] / 32768.0
                sum += v * v
            }
            loudest = max(loudest, sqrt(sum / (end - start)))
            start = end
        }
        return if (loudest > 0.0) 20.0 * log10(loudest) else Double.NEGATIVE_INFINITY
    }

    /** True when every one of the first [count] samples is zero (e.g. capture silenced by the system). */
    fun isAllZero(pcm: ShortArray, count: Int = pcm.size): Boolean {
        for (i in 0 until count) if (pcm[i].toInt() != 0) return false
        return true
    }
}
