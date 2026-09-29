package io.github.hexalyse.wisprcheap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.history.Money
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.sync.WrongPassphraseException
import io.github.hexalyse.wisprcheap.sync.SyncManager
import io.github.hexalyse.wisprcheap.sync.SyncManager.Phase
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val MIN_PASSPHRASE = 8

fun LazyListScope.syncPage(s: Settings) {
    item { SyncContent(s) }
}

@Composable
private fun SyncContent(s: Settings) {
    val sync = WisprApp.graph.sync
    val status by sync.status.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status.phase == Phase.OFF) {
            ConnectCard(sync)
        } else {
            StatusCard(sync, status)
            if (status.phase == Phase.NEEDS_KEY) UnlockCard(sync)
            SectionCard("History") {
                SwitchRow(
                    "Upload this phone's history",
                    s.sync.uploadHistory,
                    { v -> WisprApp.graph.settings.update { it.copy(sync = it.sync.copy(uploadHistory = v)) } },
                    subtitle = "Totals for all your devices. The server sees dates, durations, models, word counts and costs; the text stays encrypted.",
                )
                SwitchRow(
                    "Add the other devices' entries",
                    s.sync.downloadHistory,
                    { v -> WisprApp.graph.settings.update { it.copy(sync = it.sync.copy(downloadHistory = v)) } },
                    subtitle = "Show your computers' dictations in Activity too.",
                )
            }
            DeviceCard(sync, status)
        }
        SectionCard("What syncs") {
            Hint(
                "Speech-to-text, cleanup, command and translation settings, API keys, dictionary, translation pairs and " +
                    "custom prices, end-to-end encrypted with your sync passphrase. The bubble, recording, text insertion, " +
                    "history options and the active translation stay on this phone.",
            )
        }
    }
}

@Composable
private fun ErrorText(text: String?) {
    if (text != null) {
        Text(
            text,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }
}

/** Passphrase fields: one for an existing passphrase, two (with a length check) for a new one. */
@Composable
private fun PassphraseFields(newPassphrase: Boolean, onChange: (String?) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    fun emit() = onChange(
        when {
            !newPassphrase -> a.takeIf { it.isNotEmpty() }
            a.length >= MIN_PASSPHRASE && a == b -> a
            else -> null
        },
    )
    SecretRow(
        if (newPassphrase) "New sync passphrase" else "Sync passphrase",
        a,
        { a = it; emit() },
        supporting = if (newPassphrase) "At least $MIN_PASSPHRASE characters. You'll type it once on each device." else null,
    )
    if (newPassphrase) {
        SecretRow("Repeat it", b, { b = it; emit() }, supporting = if (b.isNotEmpty() && a != b) "They don't match" else null)
        Hint(
            "It encrypts your settings, keys, dictionary and history before they leave this phone: the server never sees it. " +
                "If you forget it, the synced data can't be decrypted (you can reset it on the server's web page).",
        )
    }
}

@Composable
private fun ConnectCard(sync: SyncManager) {
    val scope = rememberCoroutineScope()
    val link by sync.pendingLink.collectAsStateWithLifecycle()
    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(android.os.Build.MODEL) }
    var pending by remember { mutableStateOf<SyncManager.PendingPairing?>(null) }
    var passphrase by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        link?.let { (sv, c) ->
            server = sv
            code = c
            sync.pendingLink.value = null
        }
    }
    SectionCard("Connect to a sync server") {
        val p = pending
        if (p == null) {
            Hint(
                "Keep this phone and your computers in sync through your own WisprCheap sync server. On its web page, " +
                    "click \"Connect a device\" and scan the QR code, or type the address and code here.",
            )
            TextRow("Server address", server, { server = it.trim() }, placeholder = "https://sync.example.com")
            TextRow("Pairing code", code, { code = it.uppercase() }, placeholder = "ABCD-2345")
            TextRow("Device name", name, { name = it }, supporting = "Shown on the server")
            if (server.startsWith("http://")) Hint("Unencrypted connection: only use http:// on your own network.")
            ErrorText(error)
            Button(
                enabled = busy == null && server.isNotBlank() && SyncManager.normalizeCode(code).length == 8,
                onClick = {
                    busy = "Connecting…"
                    error = null
                    scope.launch {
                        runCatching { sync.beginPairing(server, code, name) }
                            .onSuccess { pending = it }
                            .onFailure { error = it.message ?: it.toString() }
                        busy = null
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("Connect") }
        } else {
            Hint(
                if (p.newPassphrase) {
                    "Connected as \"${p.me.device.name}\" (account ${p.me.user.username}). This is your first device: choose a sync passphrase."
                } else {
                    "Connected as \"${p.me.device.name}\" (account ${p.me.user.username}). Enter the sync passphrase chosen on your first device."
                },
            )
            PassphraseFields(p.newPassphrase) { passphrase = it }
            ErrorText(error)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = busy == null && passphrase != null,
                    onClick = {
                        val pass = passphrase ?: return@Button
                        busy = "Setting up the encryption and syncing…"
                        error = null
                        scope.launch {
                            try {
                                sync.finishPairing(p, pass)
                            } catch (e: WrongPassphraseException) {
                                error = "Wrong passphrase."
                            } catch (e: Exception) {
                                error = e.message ?: e.toString()
                            }
                            busy = null
                        }
                    },
                ) { Text("Continue") }
                TextButton(
                    enabled = busy == null,
                    onClick = {
                        scope.launch { sync.cancelPairing(p) }
                        pending = null
                        error = null
                    },
                ) { Text("Cancel") }
            }
        }
        busy?.let {
            Hint(it)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun StatusCard(sync: SyncManager, status: SyncManager.Status) {
    SectionCard("Status") {
        val last = status.lastSync?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "never"
        val state = when (status.phase) {
            Phase.SYNCING -> "Syncing…"
            Phase.OK -> "Synced · $last"
            Phase.OFFLINE -> "Offline, retrying later · last sync $last"
            Phase.DISCONNECTED -> "Disconnected by the server: connect this phone again"
            Phase.NEEDS_KEY -> "Sync passphrase needed"
            Phase.ERROR -> "Error · last sync $last"
            Phase.OFF -> "Off"
        }
        ListItemText("State", state)
        ListItemText("Server", status.server)
        if (status.username.isNotEmpty()) ListItemText("Account", status.username)
        ListItemText("This phone", status.deviceName)
        status.month?.let { m ->
            ListItemText("This month, all devices", "${Money.approx(m.totalUsd)} · ${Money.thousands(m.words.toInt())} words · ${status.devices} device(s)")
        }
        if (status.message.isNotEmpty() && status.phase != Phase.OK && status.phase != Phase.SYNCING) ErrorText(status.message)
        if (status.phase == Phase.SYNCING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        OutlinedButton(
            enabled = status.phase != Phase.SYNCING && status.phase != Phase.DISCONNECTED,
            onClick = { sync.syncNow() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("Sync now") }
    }
}

@Composable
private fun ListItemText(label: String, value: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun UnlockCard(sync: SyncManager) {
    val scope = rememberCoroutineScope()
    var newPassphrase by remember { mutableStateOf<Boolean?>(null) }
    var passphrase by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        newPassphrase = runCatching { sync.needsNewPassphrase() }.getOrElse {
            error = it.message
            false
        }
    }
    SectionCard("Sync passphrase") {
        val isNew = newPassphrase ?: return@SectionCard
        Hint(
            if (isNew) {
                "The encryption was reset on the server. Choose a new sync passphrase: this phone uploads its data again."
            } else {
                "The sync passphrase was changed or reset on another device. Enter it to continue syncing."
            },
        )
        PassphraseFields(isNew) { passphrase = it }
        ErrorText(error)
        Button(
            enabled = !busy && passphrase != null,
            onClick = {
                val pass = passphrase ?: return@Button
                busy = true
                error = null
                scope.launch {
                    try {
                        sync.unlock(pass)
                    } catch (e: WrongPassphraseException) {
                        error = "Wrong passphrase."
                    } catch (e: Exception) {
                        error = e.message ?: e.toString()
                    }
                    busy = false
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(if (busy) "Unlocking…" else "Unlock") }
    }
}

@Composable
private fun DeviceCard(sync: SyncManager, status: SyncManager.Status) {
    val scope = rememberCoroutineScope()
    var name by remember(status.deviceName) { mutableStateOf(status.deviceName) }
    var changing by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    SectionCard("This phone") {
        TextRow("Device name", name, { name = it })
        if (name.trim() != status.deviceName && name.isNotBlank()) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        message = runCatching { sync.rename(name) }.fold({ true to "Renamed." }, { false to (it.message ?: it.toString()) })
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("Rename") }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { changing = true }, enabled = status.phase != Phase.NEEDS_KEY) { Text("Change passphrase") }
            TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
        }
        message?.let { (ok, text) ->
            if (ok) Hint(text) else ErrorText(text)
        }
    }
    if (changing) {
        var passphrase by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) changing = false },
            title = { Text("Change the sync passphrase") },
            text = {
                Column {
                    Text("Your other devices keep working. Use the new passphrase when you connect a new one.")
                    PassphraseFields(true) { passphrase = it }
                    ErrorText(error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && passphrase != null,
                    onClick = {
                        val pass = passphrase ?: return@TextButton
                        busy = true
                        scope.launch {
                            runCatching { sync.changePassphrase(pass) }
                                .onSuccess {
                                    changing = false
                                    message = true to "Sync passphrase changed."
                                }
                                .onFailure { error = it.message ?: it.toString() }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Changing…" else "Change") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { changing = false }) { Text("Cancel") } },
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect this phone?") },
            text = { Text("It stops syncing and its token is revoked. Your settings, keys, dictionary and history stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch { sync.disconnect() }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}
