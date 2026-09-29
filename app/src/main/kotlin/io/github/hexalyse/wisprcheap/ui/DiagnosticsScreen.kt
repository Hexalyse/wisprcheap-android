package io.github.hexalyse.wisprcheap.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.a11y.WisprAccessibilityService
import io.github.hexalyse.wisprcheap.audio.MicSession
import io.github.hexalyse.wisprcheap.diag.ProbeResult
import io.github.hexalyse.wisprcheap.diag.Report
import io.github.hexalyse.wisprcheap.diag.SetupStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen() {
    val context = LocalContext.current
    val app = WisprApp.instance
    val editor by app.runtime.editor.collectAsStateWithLifecycle()
    val results by app.diagnostics.results.collectAsStateWithLifecycle()
    val alwaysShow by app.runtime.alwaysShowBubble.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf(SetupStatus.read(context)) }
    var foregroundMicRunning by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        status = SetupStatus.read(context)
        onPauseOrDispose { }
    }
    val refresh = { status = SetupStatus.read(context) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    Scaffold(topBar = { TopAppBar(title = { Text("WisprCheap · Phase 0 tests") }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Section("1. Setup") {
                    StatusRow("Microphone permission", status.mic) {
                        Button(onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant") }
                    }
                    StatusRow("Notifications permission", status.notifications) {
                        Button(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("Grant")
                        }
                    }
                    StatusRow(
                        "Accessibility service" + if (status.a11yEnabled && !editor.serviceConnected) " (enabled, not connected)" else "",
                        status.a11yEnabled && editor.serviceConnected,
                    ) {
                        Button(onClick = { openAccessibilitySettings(context) }) { Text("Open") }
                    }
                    if (!status.a11yEnabled) {
                        Text(
                            "If the switch is greyed out (\"Restricted setting\"): open App info, tap ⋮ in the top " +
                                "corner, choose \"Allow restricted settings\", confirm, then come back and tap Open again.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(onClick = { openAppInfo(context) }) { Text("Open App info") }
                    }
                    StatusRow("Battery: unrestricted (recommended)", status.batteryUnrestricted) {
                        OutlinedButton(onClick = { requestBatteryExemption(context) }) { Text("Allow") }
                    }
                    Text("Installed by: ${status.installSource}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Section("2. Live state (what the service sees)") {
                    Mono(
                        """
                        service connected: ${editor.serviceConnected}
                        input started:     ${editor.inputStarted}
                        editor package:    ${editor.editorPackage ?: "-"}
                        password field:    ${editor.isPassword}
                        selection:         ${editor.selStart}..${editor.selEnd}
                        keyboard visible:  ${editor.imeVisible} ${editor.imeBounds ?: ""}
                        focused node:      ${editor.focusedClass ?: "-"} (editable=${editor.focusedEditable})
                        active package:    ${editor.activePackage ?: "-"}
                        """.trimIndent(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Always show the bubble (debug)", modifier = Modifier.weight(1f))
                        Switch(checked = alwaysShow, onCheckedChange = { app.runtime.setAlwaysShowBubble(it) })
                    }
                }
            }
            item {
                Section("3. How to test") {
                    Text(
                        """
                        a. Open another app (Messages, WhatsApp, Chrome…) and tap a text field. The round bubble appears above the keyboard (drag it to move it).
                        b. Hold the bubble still: after 0.2 s it turns red. Speak, then release (push-to-talk test).
                        c. Tap the bubble to open the test panel, then run: Mic 3 s, Mic trampoline, Mic FGS (bg), Editor info, commitText, SET_TEXT, Paste (node), Paste (IC). For Read selection, select some text in the field first.
                        d. Repeat in several apps, then come back here and share the report.
                        Note: tests run inside this app don't prove anything about the microphone in other apps.
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    var text by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Try the bubble here") }, modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        enabled = !foregroundMicRunning && status.mic,
                        onClick = {
                            foregroundMicRunning = true
                            val requestedAt = SystemClock.elapsedRealtime()
                            thread(name = "wc-fg-mic") {
                                val s = MicSession(context.applicationContext)
                                s.start(requestedAt)
                                Thread.sleep(3000)
                                val r = s.stop()
                                app.diagnostics.add(
                                    ProbeResult(
                                        System.currentTimeMillis(), "mic.foreground", context.packageName,
                                        r.ok, r.summary(), r.details(),
                                    ),
                                )
                                foregroundMicRunning = false
                            }
                        },
                    ) { Text(if (foregroundMicRunning) "Recording 3 s, speak…" else "Baseline: mic test in this app") }
                }
            }
            item {
                Section("4. Results (${results.size})") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareReport(context, results) }) { Text("Share report") }
                        OutlinedButton(onClick = { copyReport(context, results) }) { Text("Copy") }
                        OutlinedButton(onClick = { app.diagnostics.clear() }) { Text("Clear") }
                    }
                }
            }
            items(results.asReversed()) { ResultCard(it) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(if (ok) "✅" else "⬜")
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) action()
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ResultCard(r: ProbeResult) {
    var expanded by remember { mutableStateOf(false) }
    val time = remember(r.ts) { SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(r.ts)) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (r.ok) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${if (r.ok) "OK" else "FAIL"}  ${r.test}  ·  ${r.pkg ?: "-"}", fontWeight = FontWeight.SemiBold)
            Text("$time  ${r.summary}", style = MaterialTheme.typography.bodySmall)
            if (expanded) Mono(r.details.joinToString("\n") { (k, v) -> "$k: $v" })
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    val component = ComponentName(context, WisprAccessibilityService::class.java).flattenToString()
    // Opens our service's own page directly where supported (the constant is not in the public SDK).
    val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
    runCatching { context.startActivity(details) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

@android.annotation.SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        )
    }
}

private fun shareReport(context: Context, results: List<ProbeResult>) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "WisprCheap Phase 0 report")
        .putExtra(Intent.EXTRA_TEXT, Report.build(context, results))
    context.startActivity(Intent.createChooser(send, "Share report"))
}

private fun copyReport(context: Context, results: List<ProbeResult>) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("WisprCheap report", Report.build(context, results)))
    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
}
