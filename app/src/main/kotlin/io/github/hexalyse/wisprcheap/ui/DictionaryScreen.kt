package io.github.hexalyse.wisprcheap.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.dictionary.Dictionary
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import androidx.compose.foundation.clickable

@Composable
fun DictionaryScreen() {
    val g = WisprApp.graph
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val dictionary = settings.dictionary
    var editing by remember { mutableStateOf<Int?>(null) } // index, or -1 for a new term

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ScreenHeader("Dictionary", "${dictionary.size} term(s): names and jargon to spell exactly")
            }
            item {
                Hint(
                    "Terms are sent to the speech-to-text service (ElevenLabs keyterms: +\$0.05/h, or the OpenAI prompt) " +
                        "and to the cleanup model, which also fixes the \"sounds like\" mishearings.",
                )
                if (dictionary.size > 100) Hint("More than 100 terms: ElevenLabs bills each request at least 20 s.")
            }
            items(dictionary.indices.toList(), key = { dictionary[it].term }) { i ->
                val entry = dictionary[i]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    ListItem(
                        headlineContent = { Text(entry.term) },
                        supportingContent = {
                            val notes = listOfNotNull(
                                entry.soundsLike.takeIf { it.isNotEmpty() }?.let { "sounds like: " + it.joinToString(", ") },
                                if (!Dictionary.isValidKeyterm(entry.term)) "not usable as a Scribe keyterm (sent to the LLM only)" else null,
                            )
                            if (notes.isNotEmpty()) Text(notes.joinToString("\n"))
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                g.settings.update { s -> s.copy(dictionary = s.dictionary.filterIndexed { j, _ -> j != i }) }
                            }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { editing = i },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { editing = -1 },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add term") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    editing?.let { index ->
        val existing = dictionary.getOrNull(index)
        TermDialog(
            existing = existing,
            others = dictionary.filterIndexed { j, _ -> j != index },
            onDismiss = { editing = null },
            onSave = { entry ->
                g.settings.update { s ->
                    val list = s.dictionary.toMutableList()
                    if (index in list.indices) list[index] = entry else list += entry
                    s.copy(dictionary = list)
                }
                editing = null
            },
        )
    }
}

@Composable
private fun TermDialog(existing: DictionaryEntry?, others: List<DictionaryEntry>, onDismiss: () -> Unit, onSave: (DictionaryEntry) -> Unit) {
    var term by remember { mutableStateOf(existing?.term ?: "") }
    var sounds by remember { mutableStateOf(existing?.soundsLike?.joinToString(", ") ?: "") }
    val error = remember(term) {
        if (term.isBlank()) {
            null
        } else {
            runCatching { Dictionary.validateTerm(term) }.exceptionOrNull()?.message
                ?: if (Dictionary.contains(others, term)) "\"${term.trim()}\" is already in the dictionary" else null
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = term,
                    onValueChange = { term = it },
                    label = { Text("Term") },
                    placeholder = { Text("e.g. Kubernetes") },
                    isError = error != null,
                    supportingText = { Text(error ?: "Up to 5 words, fewer than 50 characters") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sounds,
                    onValueChange = { sounds = it },
                    label = { Text("Sounds like (optional)") },
                    placeholder = { Text("e.g. cooper netties, cube ernetes") },
                    supportingText = { Text("Common mishearings, separated by commas") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = term.isNotBlank() && error == null,
                onClick = {
                    val clean = Dictionary.validateTerm(term)
                    onSave(DictionaryEntry(clean, sounds.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
