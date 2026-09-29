package io.github.hexalyse.wisprcheap.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf

class MainActivity : ComponentActivity() {
    private val open = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        open.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent { WisprTheme { AppUi(open) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open.value = intent.getStringExtra(EXTRA_OPEN)
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_LOG = "log"
        const val OPEN_KEYS = "keys"
    }
}
