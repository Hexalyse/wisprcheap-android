package io.github.hexalyse.wisprcheap.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    ACTIVITY("Activity", Icons.Rounded.History),
    DICTIONARY("Dictionary", Icons.AutoMirrored.Rounded.MenuBook),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

enum class Page(val title: String) {
    KEYS("API keys"),
    TRANSCRIPTION("Speech-to-text"),
    CLEANUP("Cleanup"),
    COMMAND("Command mode"),
    TRANSLATION("Translation"),
    BUBBLE("Bubble"),
    INSERTION("Text insertion"),
    RECORDING("Recording"),
    HISTORY("History, privacy & notifications"),
    PRICING("Prices"),
    ABOUT("About & diagnostics"),
}

@Composable
fun AppUi(open: MutableState<String?>) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    var activityTab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(open.value) {
        when (open.value) {
            MainActivity.OPEN_LOG -> {
                page = null
                tab = Tab.ACTIVITY
                activityTab = 2
            }
            MainActivity.OPEN_KEYS -> page = Page.KEYS
        }
        open.value = null
    }

    BackHandler(enabled = page != null) { page = null }
    val current = page
    if (current != null) {
        SettingsPage(current, onBack = { page = null })
        return
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (tab) {
                Tab.HOME -> HomeScreen(onOpenPage = { page = it })
                Tab.ACTIVITY -> ActivityScreen(activityTab, onTab = { activityTab = it })
                Tab.DICTIONARY -> DictionaryScreen()
                Tab.SETTINGS -> SettingsHome(onOpen = { page = it })
            }
        }
    }
}
