package io.github.hexalyse.wisprcheap.audio

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Shared state between the accessibility service, [TrampolineActivity] and [MicFgsService] (Phase 0 test). */
object Trampoline {
    @Volatile var requestedAt = 0L

    @Volatile var activityCreatedAt = 0L

    @Volatile var startError: String? = null

    /** Called by the service once startForeground() succeeded or failed, so the activity can finish. */
    @Volatile var onPromoted: (() -> Unit)? = null

    fun reset() {
        requestedAt = SystemClock.elapsedRealtime()
        activityCreatedAt = 0L
        startError = null
        onPromoted = null
    }
}

/**
 * Invisible activity: while it is visible the app is in the foreground, so it may start a microphone
 * foreground service. It finishes as soon as the service is in the foreground (or after 1.5 s).
 */
class TrampolineActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trampoline.activityCreatedAt = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        if (started) return
        started = true
        Trampoline.onPromoted = { handler.post { finishQuietly() } }
        try {
            startService(
                Intent(this, MicFgsService::class.java)
                    .putExtra(MicFgsService.EXTRA_MODE, MicFgsService.MODE_TRAMPOLINE)
                    .putExtra(MicFgsService.EXTRA_REQUESTED_AT, Trampoline.requestedAt),
            )
        } catch (e: Exception) {
            Trampoline.startError = "${e.javaClass.simpleName}: ${e.message}"
            finishQuietly()
            return
        }
        handler.postDelayed({ finishQuietly() }, 1500)
    }

    private fun finishQuietly() {
        if (isFinishing) return
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
