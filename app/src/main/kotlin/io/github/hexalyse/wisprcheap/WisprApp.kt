package io.github.hexalyse.wisprcheap

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.github.hexalyse.wisprcheap.diag.DiagnosticsStore
import io.github.hexalyse.wisprcheap.state.RuntimeState
import java.io.File

const val TAG = "WisprCheap"

class WisprApp : Application() {
    lateinit var diagnostics: DiagnosticsStore
        private set
    lateinit var runtime: RuntimeState
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        diagnostics = DiagnosticsStore(File(filesDir, "diagnostics.jsonl"))
        runtime = RuntimeState(getSharedPreferences("spike", MODE_PRIVATE))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"

        lateinit var instance: WisprApp
            private set
    }
}
