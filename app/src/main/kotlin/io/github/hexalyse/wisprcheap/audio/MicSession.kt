package io.github.hexalyse.wisprcheap.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import io.github.hexalyse.wisprcheap.core.audio.Pcm
import java.util.Locale

/** Measurements of one capture, used by the Phase 0 microphone tests. */
data class MicResult(
    val error: String?,
    val samples: Int,
    val peakDb: Double,
    val allZero: Boolean,
    val startLatencyMs: Long?,
    val firstDataMs: Long?,
    val firstNonZeroMs: Long?,
    val silencedAtStart: Boolean?,
    val silencedSeen: Boolean,
    val readError: Int,
    val routedDevice: String?,
) {
    val durationMs: Double get() = Pcm.durationMs(samples)

    /** The system delivered real audio (the level itself depends on whether the user spoke). */
    val ok: Boolean get() = error == null && samples > 0 && !allZero && !silencedSeen && readError == 0

    fun summary(): String = when {
        error != null -> "failed: $error"
        samples == 0 -> "no audio data (read error $readError)"
        allZero -> "all zeros: silenced by the system" + if (silencedSeen) " (isClientSilenced=true)" else ""
        else -> String.format(
            Locale.ROOT, "%.1f s, peak %.1f dBFS%s, first data after %d ms%s",
            durationMs / 1000, peakDb, if (peakDb < -55) " (quiet: did you speak?)" else "",
            firstDataMs ?: -1, if (silencedSeen) ", SILENCED at some point" else "",
        )
    }

    fun details(): List<Pair<String, String>> = listOf(
        "error" to (error ?: "none"),
        "samples" to "$samples (${String.format(Locale.ROOT, "%.2f", durationMs / 1000)} s)",
        "peakDbfs" to String.format(Locale.ROOT, "%.1f", peakDb),
        "allZero" to "$allZero",
        "startRecording latency ms" to "${startLatencyMs ?: "-"}",
        "first data ms" to "${firstDataMs ?: "-"}",
        "first non-zero ms" to "${firstNonZeroMs ?: "-"}",
        "isClientSilenced at start" to "${silencedAtStart ?: "-"}",
        "isClientSilenced seen" to "$silencedSeen",
        "read error" to "$readError",
        "routed device" to (routedDevice ?: "-"),
    )
}

/**
 * One microphone capture: 16 kHz mono PCM16, VOICE_RECOGNITION source, opened on [start] and released on [stop].
 * Latencies are measured from `requestedAt` (e.g. the moment the user touched the bubble).
 */
class MicSession(
    private val context: Context,
    private val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    private var buffer = ShortArray(Pcm.SAMPLE_RATE * 30)
    private var count = 0
    private var requestedAt = 0L
    private var startedAt = 0L

    @Volatile private var firstDataAt = 0L

    @Volatile private var firstNonZeroAt = 0L

    @Volatile private var silencedSeen = false
    private var silencedAtStart: Boolean? = null

    @Volatile private var readError = 0
    private var error: String? = null
    private var routedDevice: String? = null

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (record?.activeRecordingConfiguration?.isClientSilenced == true) silencedSeen = true
        }
    }

    /** Returns false (with the reason in the result) if the microphone could not be started. */
    @SuppressLint("MissingPermission")
    fun start(requestedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
        requestedAt = requestedAtMs
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "RECORD_AUDIO permission not granted"
            return false
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                error = "getMinBufferSize returned $minBuf (16 kHz mono not supported?)"
                return false
            }
            val rec = AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(Pcm.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, Pcm.SAMPLE_RATE * 2 / 5))
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                error = "AudioRecord not initialized"
                return false
            }
            record = rec
            rec.registerAudioRecordingCallback(context.mainExecutor, callback)
            rec.startRecording()
            startedAt = SystemClock.elapsedRealtime()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error = "startRecording() did not start (state ${rec.recordingState})"
                release()
                return false
            }
            silencedAtStart = rec.activeRecordingConfiguration?.isClientSilenced
            routedDevice = rec.routedDevice?.let { "${it.productName} (type ${it.type})" }
            running = true
            thread = Thread({ loop(rec) }, "wc-mic-read").also { it.start() }
            return true
        } catch (e: Exception) {
            error = "${e.javaClass.simpleName}: ${e.message}"
            release()
            return false
        }
    }

    private fun loop(rec: AudioRecord) {
        val chunk = ShortArray(320) // 20 ms
        var lastCheck = 0L
        while (running) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n < 0) {
                readError = n
                break
            }
            if (n == 0) continue
            val now = SystemClock.elapsedRealtime()
            if (firstDataAt == 0L) firstDataAt = now
            if (firstNonZeroAt == 0L) {
                for (i in 0 until n) if (chunk[i].toInt() != 0) {
                    firstNonZeroAt = now
                    break
                }
            }
            if (count + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, count + n))
            System.arraycopy(chunk, 0, buffer, count, n)
            count += n
            if (now - lastCheck > 250) {
                lastCheck = now
                if (rec.activeRecordingConfiguration?.isClientSilenced == true) silencedSeen = true
            }
        }
    }

    fun stop(): MicResult {
        running = false
        runCatching { record?.stop() }
        thread?.join(1500)
        release()
        fun since(t: Long) = if (t == 0L) null else t - requestedAt
        return MicResult(
            error = error,
            samples = count,
            peakDb = Pcm.loudestWindowDb(buffer, count),
            allZero = Pcm.isAllZero(buffer, count),
            startLatencyMs = since(startedAt),
            firstDataMs = since(firstDataAt),
            firstNonZeroMs = since(firstNonZeroAt),
            silencedAtStart = silencedAtStart,
            silencedSeen = silencedSeen,
            readError = readError,
            routedDevice = routedDevice,
        )
    }

    /** Captured samples (valid after [stop]). */
    fun pcm(): ShortArray = buffer.copyOf(count)

    private fun release() {
        val rec = record ?: return
        record = null
        runCatching { rec.unregisterAudioRecordingCallback(callback) }
        runCatching { rec.release() }
    }
}
