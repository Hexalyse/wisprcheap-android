package io.github.hexalyse.wisprcheap.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.Money
import io.github.hexalyse.wisprcheap.core.history.Stats
import io.github.hexalyse.wisprcheap.core.history.Timestamps
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivityScreen(tab: Int, onTab: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { ScreenHeader("Activity") }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("History", "Stats", "Log").forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> HistoryTab()
            1 -> StatsTab()
            else -> LogTab()
        }
    }
}

/** App name for a package (launcher apps are visible through the manifest's <queries>). */
fun appLabel(context: Context, pkg: String?): String {
    pkg ?: return "Unknown app"
    return runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(pkg)
}

private val DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault())
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    val g = WisprApp.graph
    val scope = rememberCoroutineScope()
    val entries by g.history.entries.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/jsonl")) { uri ->
        if (uri != null) scope.launch { context.contentResolver.openOutputStream(uri)?.use { g.history.export(it) } }
    }
    val labels = remember { HashMap<String, String>() }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} entries", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { export.launch("history.jsonl") }, enabled = entries.isNotEmpty()) { Text("Export") }
                TextButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) { Text("Clear") }
            }
        }
        if (entries.isEmpty()) {
            item { Hint("Nothing yet. Your dictations will appear here.") }
        }
        val reversed = entries.asReversed()
        itemsIndexed(reversed, key = { i, e -> "${e.ts}#$i" }) { i, e ->
            val date = Timestamps.parse(e.ts)?.atZone(zone)
            val previous = reversed.getOrNull(i - 1)?.let { Timestamps.parse(it.ts)?.atZone(zone)?.toLocalDate() }
            if (date != null && date.toLocalDate() != previous) {
                Text(
                    DAY.format(date).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            val app = e.app?.let { pkg -> labels.getOrPut(pkg) { appLabel(context, pkg) } }
            HistoryItem(e, date?.let { TIME.format(it) } ?: "", app) { selected = e }
        }
    }

    selected?.let { e ->
        HistoryDetail(
            e,
            onDismiss = { selected = null },
            onDelete = {
                scope.launch { g.history.delete(e) }
                selected = null
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the history?") },
            text = { Text("All entries and saved recordings are deleted. This month's totals restart from zero.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { g.history.clear() }
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryItem(e: HistoryEntry, time: String, app: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (e.error != null) cs.errorContainer else cs.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("  ·  ${app ?: "—"}", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                val badges = listOfNotNull(
                    if (e.isCommand) "Command" else null,
                    e.translation?.let { "→ " + it.substringAfter('>').uppercase() },
                    if (e.retry == true) "Retry" else null,
                    if (e.error != null) "Failed" else null,
                )
                if (badges.isNotEmpty()) Text(badges.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
            }
            Text(
                e.error ?: e.text.ifEmpty { "(nothing recognised)" },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "%.1f s  ·  %d words%s".format(Locale.ROOT, e.durationSec, e.words, e.costUsd.total?.let { "  ·  ${Money.table(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryDetail(e: HistoryEntry, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val g = WisprApp.graph
    val zone = ZoneId.systemDefault()
    val lines = buildList {
        add("When" to (Timestamps.parse(e.ts)?.atZone(zone)?.let { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss").format(it) } ?: e.ts))
        add("App" to appLabel(context, e.app))
        add("Mode" to if (e.isCommand) "Command" else "Dictation")
        add("Audio" to "%.2f s".format(Locale.ROOT, e.durationSec))
        add("Transcription" to "${e.transcription.provider} / ${e.transcription.model} · ${e.transcription.ms} ms")
        e.polish?.let { p ->
            add((if (e.isCommand) "Command LLM" else if (e.translation != null) "Translation" else "Cleanup") to "${p.model} · ${p.ms} ms · ${p.inputTokens} in / ${p.outputTokens} out")
            p.error?.let { add("LLM error" to it) }
        }
        e.polishSkipped?.let { add("Cleanup" to "skipped ($it words)") }
        e.translation?.let { add("Translation" to it) }
        if (e.isCommand) add("Selection" to (e.selection ?: "(none)"))
        add("Raw" to e.raw.ifEmpty { "—" })
        add("Text" to e.text.ifEmpty { "—" })
        add("Delivered" to listOfNotNull(e.delivered?.id, e.insertMethod?.let { "($it)" }).joinToString(" ").ifEmpty { "—" })
        add(
            "Cost" to "transcription ${e.costUsd.transcription?.let(Money::table) ?: "?"} + LLM ${e.costUsd.polish?.let(Money::table) ?: "–"} = " +
                (e.costUsd.total?.let(Money::table) ?: "?"),
        )
        e.error?.let { add("Error" to it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e.error != null) "Failed recording" else if (e.isCommand) "Command" else "Dictation") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { (k, v) ->
                        Column {
                            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (e.error != null && e.audioFile != null) {
                    TextButton(onClick = {
                        if (!g.retryEntry(e)) android.widget.Toast.makeText(context, "The recording is no longer available", android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }) { Text("Retry") }
                }
                if (e.text.isNotEmpty()) TextButton(onClick = { copy(context, e.text) }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = { TextButton(onClick = onDelete) { Text("Delete") } },
    )
}

@Composable
private fun StatsTab() {
    val entries by WisprApp.graph.history.entries.collectAsStateWithLifecycle()
    val months = remember(entries) { Stats.byMonth(entries, ZoneId.systemDefault()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (months.isEmpty()) item { Hint("No successful dictation yet.") }
        items(months, key = { it.month }) { m ->
            SectionCard("${Stats.monthName(m.month)} ${m.month.substringBefore('-')}") {
                StatLine("Dictations", "${m.dictations}" + if (m.commands > 0) "  (+ ${m.commands} commands)" else "")
                StatLine("Words", Money.thousands(m.words))
                StatLine("Audio", "%.1f min".format(Locale.ROOT, m.audioMinutes))
                StatLine("Transcription", Money.table(m.transcribeUsd))
                StatLine("Cleanup / LLM", Money.table(m.polishUsd))
                StatLine("Total", Money.table(m.totalUsd), bold = true)
                StatLine("Per 10k words", m.per10kWords?.let(Money::table) ?: "—")
                if (m.unknownPrice > 0) Hint("${m.unknownPrice} entries used a model without a known price (counted as \$0). Add it in Settings → Prices.")
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun LogTab() {
    val context = LocalContext.current
    val log = WisprApp.graph.log
    val lines by log.lines.collectAsStateWithLifecycle()
    var errorsOnly by remember { mutableStateOf(false) }
    val shown = remember(lines, errorsOnly) { if (errorsOnly) lines.filter { it.error } else lines }
    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = errorsOnly, onClick = { errorsOnly = !errorsOnly }, label = { Text("Errors only") })
            AssistChip(onClick = { copy(context, log.allText()) }, label = { Text("Copy") })
            AssistChip(onClick = { share(context, log.allText(), "WisprCheap log") }, label = { Text("Share") })
            AssistChip(onClick = { log.clear() }, label = { Text("Clear") })
        }
        SelectionContainer {
            LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(shown.size) { i ->
                    val line = shown[i]
                    Text(
                        line.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = if (line.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
