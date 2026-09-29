package io.github.hexalyse.wisprcheap.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.sync.SyncManager

class MainActivity : ComponentActivity() {
    private val open = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        open.value = target(intent)
        setContent { WisprTheme { AppUi(open) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open.value = target(intent)
    }

    override fun onResume() {
        super.onResume()
        WisprApp.graph.sync.onAppOpened()
    }

    /** What to show: an extra from a notification, or a `wisprcheap://pair` link. */
    private fun target(intent: Intent?): String? {
        SyncManager.parseLink(intent?.data)?.let { link ->
            WisprApp.graph.sync.pendingLink.value = link
            return OPEN_SYNC
        }
        return intent?.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_LOG = "log"
        const val OPEN_KEYS = "keys"
        const val OPEN_SYNC = "sync"
    }
}
