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
import io.github.hexalyse.wisprcheap.core.settings.AudioSourceSetting
import kotlin.math.log10
import kotlin.math.sqrt

/** Outcome of one capture. */
data class MicResult(
    val error: String?,
    val samples: Int,
    val firstDataMs: Long?,
    val silencedSeen: Boolean,
)

/**
 * One microphone capture: 16 kHz mono PCM16, opened on [start] and released on [stop] (desktop parity: the mic
 * indicator is only on while recording). [onLevel] receives a 0..1 level every ~50 ms, from the capture thread.
 */
class MicSession(
    private val context: Context,
    source: AudioSourceSetting = AudioSourceSetting.VOICE_RECOGNITION,
    private val onLevel: ((Float) -> Unit)? = null,
) {
    private val audioSource = when (source) {
        AudioSourceSetting.VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        AudioSourceSetting.MIC -> MediaRecorder.AudioSource.MIC
        AudioSourceSetting.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
    }
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    private var buffer = ShortArray(Pcm.SAMPLE_RATE * 30)
    private var count = 0
    private var requestedAt = 0L

    @Volatile private var firstDataAt = 0L

    @Volatile private var silencedSeen = false

    @Volatile private var readError = 0
    var error: String? = null
        private set

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            if (record?.activeRecordingConfiguration?.isClientSilenced == true) silencedSeen = true
        }
    }

    /** Opens the microphone. Returns false (with [error] set) if it could not start. Blocking: call off the main thread. */
    @SuppressLint("MissingPermission")
    fun start(requestedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
        requestedAt = requestedAtMs
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "the microphone permission is not granted"
            return false
        }
        try {
            val minBuf = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                error = "16 kHz mono capture is not supported (getMinBufferSize=$minBuf)"
                return false
            }
            val rec = AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(audioSource)
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
                error = "the microphone could not be initialized"
                return false
            }
            record = rec
            rec.registerAudioRecordingCallback(context.mainExecutor, callback)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error = "the microphone did not start (it may be used by another app)"
                release()
                return false
            }
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
        var levelSum = 0.0
        var levelCount = 0
        while (running) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n < 0) {
                readError = n
                break
            }
            if (n == 0) continue
            val now = SystemClock.elapsedRealtime()
            if (firstDataAt == 0L) firstDataAt = now
            if (count + n > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, count + n))
            System.arraycopy(chunk, 0, buffer, count, n)
            count += n
            if (onLevel != null) {
                for (i in 0 until n) {
                    val v = chunk[i] / 32768.0
                    levelSum += v * v
                }
                levelCount += n
                if (levelCount >= 800) {
                    val db = 20 * log10(sqrt(levelSum / levelCount).coerceAtLeast(1e-6))
                    onLevel.invoke(((db + 60) / 50).toFloat().coerceIn(0f, 1f))
                    levelSum = 0.0
                    levelCount = 0
                }
            }
            if (now - lastCheck > 250) {
                lastCheck = now
                if (rec.activeRecordingConfiguration?.isClientSilenced == true) silencedSeen = true
            }
        }
    }

    /** Stops and releases the microphone. Blocking. */
    fun stop(): MicResult {
        running = false
        runCatching { record?.stop() }
        thread?.join(1500)
        release()
        if (error == null && readError != 0) error = "microphone read error $readError"
        return MicResult(error, count, if (firstDataAt == 0L) null else firstDataAt - requestedAt, silencedSeen)
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
