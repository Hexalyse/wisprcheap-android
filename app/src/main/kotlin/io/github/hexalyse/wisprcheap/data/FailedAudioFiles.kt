package io.github.hexalyse.wisprcheap.data

import io.github.hexalyse.wisprcheap.core.audio.Pcm
import io.github.hexalyse.wisprcheap.core.history.Timestamps
import io.github.hexalyse.wisprcheap.core.pipeline.FailedAudioStore
import java.io.File
import java.time.Instant

/** WAV copies of recordings whose transcription failed, so they can be retried later. */
class FailedAudioFiles(private val dir: File) : FailedAudioStore {
    override fun save(pcm: ShortArray, startedAt: Instant): String? = runCatching {
        dir.mkdirs()
        val name = "failed-" + Timestamps.iso(startedAt).replace(':', '-').replace('.', '-') + ".wav"
        val f = File(dir, name)
        f.writeBytes(Pcm.encodeWav(pcm))
        f.absolutePath
    }.getOrNull()

    /** PCM samples of a saved WAV (16 kHz mono 16-bit, 44-byte header), or null. */
    fun read(path: String): ShortArray? = runCatching {
        val bytes = File(path).readBytes()
        if (bytes.size < 44) return null
        val n = (bytes.size - 44) / 2
        ShortArray(n) { i -> ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort() }
    }.getOrNull()

    /** Deletes recordings older than [days] days; returns how many were deleted. */
    fun cleanup(days: Int): Int {
        if (days <= 0) return 0
        val limit = System.currentTimeMillis() - days * 86_400_000L
        return dir.listFiles()?.count { it.isFile && it.lastModified() < limit && it.delete() } ?: 0
    }
}
