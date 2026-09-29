package io.github.hexalyse.wisprcheap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.Money
import io.github.hexalyse.wisprcheap.core.history.MonthTotals
import io.github.hexalyse.wisprcheap.core.history.Stats
import io.github.hexalyse.wisprcheap.core.settings.Validation
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import io.github.hexalyse.wisprcheap.overlay.RecordRed
import io.github.hexalyse.wisprcheap.sync.SyncManager
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun HomeScreen(onOpenPage: (Page) -> Unit) {
    val context = LocalContext.current
    val g = WisprApp.graph
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val keys by g.secrets.keys.collectAsStateWithLifecycle()
    val editor by g.state.editor.collectAsStateWithLifecycle()
    val recording by g.state.recording.collectAsStateWithLifecycle()
    val pending by g.queue.pending.collectAsStateWithLifecycle()
    val busy by g.state.busyLabel.collectAsStateWithLifecycle()
    val lastText by g.state.lastText.collectAsStateWithLifecycle()
    val lastFailed by g.state.lastFailed.collectAsStateWithLifecycle()
    val entries by g.history.entries.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(SetupStatus.read(context)) }
    LifecycleResumeEffect(Unit) {
        setup = SetupStatus.read(context)
        onPauseOrDispose { }
    }
    val refresh = { setup = SetupStatus.read(context) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val issues = remember(settings, keys) { Validation.issues(settings, keys) }
    val blocking = issues.firstOrNull { it.blocking }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("WisprCheap") }
        item {
            val (title, subtitle, look) = when {
                !setup.a11yEnabled || !editor.serviceConnected -> Triple("Setup needed", "Turn on the accessibility service below.", StatusLook.ERROR)
                !setup.mic -> Triple("Setup needed", "Allow the microphone below.", StatusLook.ERROR)
                blocking != null -> Triple("Setup needed", blocking.message, StatusLook.ERROR)
                settings.bubble.paused -> Triple("Paused", "The bubble is hidden until you resume.", StatusLook.PAUSED)
                recording -> Triple("Recording…", "Speak, then release or tap send.", StatusLook.RECORDING)
                pending > 0 -> Triple(busy, if (pending > 1) "$pending recordings in the queue" else "Almost done", StatusLook.BUSY)
                else -> Triple("Ready", "Tap a text field in any app. Hold the bubble to talk, or tap it for hands-free.", StatusLook.READY)
            }
            StatusCard(title, subtitle, look, paused = settings.bubble.paused) { paused ->
                g.settings.update { it.copy(bubble = it.bubble.copy(paused = paused)) }
            }
        }
        if (!setup.essentialsDone || !setup.notifications || blocking != null || !editor.serviceConnected) {
            item {
                SectionCard("Finish setup") {
                    SetupItem("Microphone", "Needed to record your voice.", setup.mic) {
                        Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow") }
                    }
                    SetupItem("Notifications", "To tell you when something fails.", setup.notifications) {
                        OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                    }
                    SetupItem(
                        "Accessibility service",
                        "Shows the bubble on text fields and inserts the text. In the list, open \"WisprCheap dictation " +
                            "bubble\" (under Downloaded apps) and turn it on. If the switch is greyed out: App info → ⋮ → " +
                            "Allow restricted settings, then try again.",
                        setup.a11yEnabled && editor.serviceConnected,
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Button(onClick = { SystemScreens.accessibility(context) }) { Text("Open") }
                            TextButton(onClick = { SystemScreens.appInfo(context) }) { Text("App info") }
                        }
                    }
                    SetupItem("API key", blocking?.message ?: "Keys are set.", blocking == null) {
                        Button(onClick = { onOpenPage(Page.KEYS) }) { Text("Add") }
                    }
                    SetupItem("Battery (recommended)", "Lets Android keep WisprCheap running.", setup.batteryUnrestricted) {
                        OutlinedButton(onClick = { SystemScreens.battery(context) }) { Text("Allow") }
                    }
                }
            }
        }
        item { MonthCard(entries) }
        val pairs = TranslationPairs.build(settings.translation.pairs).first
        if (pairs.isNotEmpty()) {
            item {
                SectionCard("Translate dictations") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = settings.translation.active == null || pairs.none { it.id == settings.translation.active },
                            onClick = { g.settings.update { it.copy(translation = it.translation.copy(active = null)) } },
                            label = { Text("Off") },
                        )
                        pairs.forEach { p ->
                            FilterChip(
                                selected = settings.translation.active == p.id,
                                onClick = { g.settings.update { it.copy(translation = it.translation.copy(active = p.id)) } },
                                label = { Text(p.label) },
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Try it here") {
                var text by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Tap here, then use the bubble") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Hint("Hold the bubble still: after a moment it turns red, speak, then release. Tap it instead for hands-free with Cancel / Send. Slide up while holding for a command on the selected text.")
            }
        }
        lastText?.let { text ->
            item {
                SectionCard("Last dictation") {
                    Text(text, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        TextButton(onClick = { copy(context, text) }) {
                            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        TextButton(onClick = { share(context, text) }) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                    }
                }
            }
        }
        lastFailed?.let { failed ->
            item {
                SectionCard("Last failed recording") {
                    Text(failed.message, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    FilledTonalButton(onClick = { g.retryLastFailed() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry (result copied to the clipboard)")
                    }
                }
            }
        }
    }
}

private enum class StatusLook { READY, RECORDING, BUSY, PAUSED, ERROR }

@Composable
private fun StatusCard(title: String, subtitle: String, look: StatusLook, paused: Boolean, onPause: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val (bg, fg) = when (look) {
                StatusLook.READY -> cs.primary to cs.onPrimary
                StatusLook.RECORDING -> RecordRed to androidx.compose.ui.graphics.Color.White
                StatusLook.BUSY -> cs.surface to cs.primary
                StatusLook.PAUSED -> cs.outline to cs.surface
                StatusLook.ERROR -> cs.error to cs.onError
            }
            Box(Modifier.size(56.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
                when (look) {
                    StatusLook.BUSY -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    StatusLook.PAUSED -> Icon(Icons.Rounded.Pause, null, tint = fg)
                    StatusLook.ERROR -> Icon(Icons.Rounded.PriorityHigh, null, tint = fg)
                    else -> Icon(Icons.Rounded.Mic, null, tint = fg)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = !paused, onCheckedChange = { onPause(!it) })
                Text(if (paused) "Paused" else "On", style = MaterialTheme.typography.labelSmall, color = cs.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun SetupItem(title: String, subtitle: String, done: Boolean, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) action()
    }
}

@Composable
private fun MonthCard(allEntries: List<HistoryEntry>) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val month = "%04d-%02d".format(today.year, today.monthValue)
    val sync = WisprApp.graph.sync
    val syncStatus by sync.status.collectAsStateWithLifecycle()
    val serverMonth = syncStatus.month?.takeIf { it.month == month && syncStatus.phase != SyncManager.Phase.OFF }
    var allDevices by rememberSaveable { mutableStateOf(false) }
    val showAll = allDevices && serverMonth != null
    // Other devices' entries (sync download) count only in "All devices".
    val entries = remember(allEntries, showAll, syncStatus.phase) {
        if (showAll) allEntries else allEntries.filter { it.device == null || it.device == sync.deviceId }
    }
    val totals = remember(entries, showAll, serverMonth) {
        val m = serverMonth
        if (allDevices && m != null) {
            MonthTotals(month, m.totalUsd, m.words.toInt(), m.entries.toInt())
        } else {
            Stats.monthTotals(entries, month, zone)
        }
    }
    val perDay = remember(entries) {
        val days = IntArray(today.lengthOfMonth())
        entries.forEach { e ->
            val d = io.github.hexalyse.wisprcheap.core.history.Timestamps.parse(e.ts)?.atZone(zone)?.toLocalDate() ?: return@forEach
            if (d.year == today.year && d.monthValue == today.monthValue) days[d.dayOfMonth - 1] += e.words
        }
        days
    }
    SectionCard(Stats.monthName(month)) {
        if (serverMonth != null) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !allDevices, onClick = { allDevices = false }, label = { Text("This phone") })
                FilterChip(
                    selected = allDevices,
                    onClick = { allDevices = true },
                    label = { Text(if (syncStatus.devices > 1) "All ${syncStatus.devices} devices" else "All devices") },
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(Money.approx(totals.costUsd), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("${Money.thousands(totals.words)} words", style = MaterialTheme.typography.bodyMedium)
                Text("${totals.dictations} dictations", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val barColor = MaterialTheme.colorScheme.primary
        val emptyColor = MaterialTheme.colorScheme.surfaceVariant
        Canvas(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
            val max = (perDay.maxOrNull() ?: 0).coerceAtLeast(1)
            val step = size.width / perDay.size
            perDay.forEachIndexed { i, words ->
                val h = if (words == 0) 2.dp.toPx() else (size.height * words / max).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (words == 0) emptyColor else barColor,
                    topLeft = Offset(i * step + step * 0.15f, size.height - h),
                    size = Size(step * 0.7f, h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("WisprCheap", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun share(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (subject != null) send.putExtra(Intent.EXTRA_SUBJECT, subject)
    context.startActivity(Intent.createChooser(send, "Share"))
}
