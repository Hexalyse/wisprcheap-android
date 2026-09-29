package io.github.hexalyse.wisprcheap.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hexalyse.wisprcheap.R
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.data.SettingsRepository
import io.github.hexalyse.wisprcheap.ui.MainActivity

/** Error notifications (desktop: balloons/toasts). Tapping one opens the log. */
class Notifier(private val context: Context, private val settings: SettingsRepository) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ERRORS, "Errors", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Failed transcriptions, translations, commands and microphone problems"
            },
        )
    }

    fun error(title: String, message: String, retry: Boolean = false, openSettings: Boolean = false) {
        if (!settings.current.notifications.errors) return
        val text = if (message.length > 250) message.take(247) + "..." else message
        val builder = Notification.Builder(context, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(if (openSettings) MainActivity.OPEN_KEYS else MainActivity.OPEN_LOG))
        if (retry) {
            val intent = Intent(context, NotificationReceiver::class.java).setAction(ACTION_RETRY)
            val pending = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Retry", pending).build())
        }
        runCatching { manager.notify(if (retry) ID_RETRY else ID_ERROR, builder.build()) }
    }

    fun info(title: String, message: String) {
        if (!settings.current.notifications.errors) return
        val builder = Notification.Builder(context, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
        runCatching { manager.notify(ID_INFO, builder.build()) }
    }

    fun cancelRetry() = manager.cancel(ID_RETRY)

    private fun openApp(destination: String): PendingIntent = PendingIntent.getActivity(
        context,
        destination.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ERRORS = "errors"
        const val ACTION_RETRY = "io.github.hexalyse.wisprcheap.RETRY"
        private const val ID_ERROR = 1
        private const val ID_RETRY = 2
        private const val ID_INFO = 3
    }
}

/** Handles notification actions ("Retry"). */
class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Notifier.ACTION_RETRY) {
            WisprApp.graph.notifier.cancelRetry()
            WisprApp.graph.retryLastFailed()
        }
    }
}
