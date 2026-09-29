package io.github.hexalyse.wisprcheap.audio

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import io.github.hexalyse.wisprcheap.R
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService
import io.github.hexalyse.wisprcheap.diag.ProbeResult
import kotlin.concurrent.thread

/**
 * Phase 0 fallback test: a microphone foreground service recording 3 s.
 * Started with startService() (not startForegroundService()), so a refused startForeground() doesn't crash the app.
 */
class MicFgsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "?"
        val requestedAt = intent?.getLongExtra(EXTRA_REQUESTED_AT, 0L)?.takeIf { it > 0 }
            ?: SystemClock.elapsedRealtime()
        val test = if (mode == MODE_TRAMPOLINE) "mic.trampoline" else "mic.fgs-from-service"
        val pkgBefore = WisprApp.instance.runtime.editor.value.targetPackage
        val notification = Notification.Builder(this, WisprApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("WisprCheap")
            .setContentText("Microphone test ($mode)")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Trampoline.onPromoted?.invoke()
            WisprApp.instance.diagnostics.add(
                ProbeResult(
                    System.currentTimeMillis(), test, pkgBefore, false,
                    "startForeground refused: ${e.javaClass.simpleName}",
                    listOf("exception" to "${e.javaClass.name}: ${e.message}") + trampolineDetails(mode),
                ),
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Trampoline.onPromoted?.invoke()
        thread(name = "wc-fgs-mic") {
            val session = MicSession(this)
            session.start(requestedAt)
            Thread.sleep(3000)
            val r = session.stop()
            // Let the focus settle, then check whether the text field got its input connection back.
            Thread.sleep(500)
            val after = WisprApp.instance.runtime.editor.value
            WisprApp.instance.diagnostics.add(
                ProbeResult(
                    System.currentTimeMillis(), test, pkgBefore, r.ok, r.summary(),
                    r.details() + trampolineDetails(mode) + listOf(
                        "editor after" to after.describe(),
                        "a11y service connected" to "${WisprAccessibilityService.instance != null}",
                    ),
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun trampolineDetails(mode: String): List<Pair<String, String>> =
        if (mode != MODE_TRAMPOLINE) emptyList() else listOf(
            "activity started after ms" to
                (Trampoline.activityCreatedAt.takeIf { it > 0 }?.let { "${it - Trampoline.requestedAt}" } ?: "-"),
        )

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_REQUESTED_AT = "requested_at"
        const val MODE_TRAMPOLINE = "trampoline"
        const val MODE_DIRECT = "direct"
        private const val NOTIFICATION_ID = 42
    }
}
