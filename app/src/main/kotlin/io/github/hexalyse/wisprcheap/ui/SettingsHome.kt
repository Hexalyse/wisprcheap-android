package io.github.hexalyse.wisprcheap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BubbleChart
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.settings.Provider
import io.github.hexalyse.wisprcheap.core.settings.Validation
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs

@Composable
fun SettingsHome(onOpen: (Page) -> Unit) {
    val g = WisprApp.graph
    val s by g.settings.settings.collectAsStateWithLifecycle()
    val keys by g.secrets.keys.collectAsStateWithLifecycle()
    val missing = Validation.issues(s, keys).count { it.path.endsWith("apiKey") }
    val t = s.transcription
    val rows: List<Triple<Page, ImageVector, String>> = listOf(
        Triple(Page.KEYS, Icons.Rounded.Key, if (missing > 0) "$missing key(s) missing" else "ElevenLabs, OpenAI and other endpoints"),
        Triple(
            Page.TRANSCRIPTION, Icons.Rounded.GraphicEq,
            if (t.provider == Provider.ELEVENLABS) "ElevenLabs · ${t.elevenlabs.model} · ${t.language}" else "OpenAI · ${t.openai.model} · ${t.language}",
        ),
        Triple(Page.CLEANUP, Icons.Rounded.CleaningServices, if (s.polish.enabled) s.polish.model else "Off"),
        Triple(Page.COMMAND, Icons.Rounded.AutoAwesome, if (s.command.enabled) (s.command.llm.model ?: s.polish.model) else "Off"),
        Triple(
            Page.TRANSLATION, Icons.Rounded.Translate,
            TranslationPairs.build(s.translation.pairs).first.let { p ->
                if (p.isEmpty()) "No language pair" else "${p.size} pair(s), active: ${TranslationPairs.active(s.translation)?.label ?: "off"}"
            },
        ),
        Triple(Page.BUBBLE, Icons.Rounded.BubbleChart, "When it shows, size, gestures, haptics, excluded apps"),
        Triple(Page.INSERTION, Icons.Rounded.Keyboard, "How the text gets into the field, spacing"),
        Triple(Page.RECORDING, Icons.Rounded.Mic, "Durations, silence threshold, audio source"),
        Triple(Page.HISTORY, Icons.Rounded.Security, "History, saved recordings, log, notifications"),
        Triple(Page.PRICING, Icons.Rounded.AttachMoney, "Price table and custom model prices"),
        Triple(Page.ABOUT, Icons.Rounded.Info, "Version, service status, microphone test"),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Settings") }
        item {
            SectionCard {
                rows.forEach { (page, icon, summary) ->
                    ClickRow(
                        title = page.title,
                        subtitle = summary,
                        leading = { Icon(icon, contentDescription = null) },
                        trailing = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                        onClick = { onOpen(page) },
                    )
                }
            }
        }
    }
}
