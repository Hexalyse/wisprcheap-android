package io.github.hexalyse.wisprcheap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexalyse.wisprcheap.BuildConfig
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.audio.MicSession
import io.github.hexalyse.wisprcheap.core.audio.Pcm
import io.github.hexalyse.wisprcheap.core.pipeline.ConnectionTest
import io.github.hexalyse.wisprcheap.core.pricing.Pricing
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.AudioSourceSetting
import io.github.hexalyse.wisprcheap.core.settings.BubbleSize
import io.github.hexalyse.wisprcheap.core.settings.DEFAULT_POLISH_INSTRUCTIONS
import io.github.hexalyse.wisprcheap.core.settings.InsertMethod
import io.github.hexalyse.wisprcheap.core.settings.LlmOverride
import io.github.hexalyse.wisprcheap.core.settings.LlmResolve
import io.github.hexalyse.wisprcheap.core.settings.PriceOverride
import io.github.hexalyse.wisprcheap.core.settings.Provider
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.ShowWhen
import io.github.hexalyse.wisprcheap.core.settings.TranslationPairSetting
import io.github.hexalyse.wisprcheap.core.translate.Languages
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.concurrent.thread

private fun update(transform: (Settings) -> Settings) = WisprApp.graph.settings.update(transform)
private fun updateKeys(transform: (ApiKeys) -> ApiKeys) = WisprApp.graph.secrets.update(transform)

@Composable
fun SettingsPage(page: Page, onBack: () -> Unit) {
    val g = WisprApp.graph
    val s by g.settings.settings.collectAsStateWithLifecycle()
    val keys by g.secrets.keys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PageScaffold(page.title, onBack) {
        when (page) {
            Page.KEYS -> keysPage(s, keys)
            Page.TRANSCRIPTION -> transcriptionPage(s)
            Page.CLEANUP -> cleanupPage(s, keys)
            Page.COMMAND -> commandPage(s, keys)
            Page.TRANSLATION -> translationPage(s, keys)
            Page.BUBBLE -> bubblePage(s, context)
            Page.INSERTION -> insertionPage(s, context)
            Page.RECORDING -> recordingPage(s)
            Page.HISTORY -> historyPage(s)
            Page.PRICING -> pricingPage(s)
            Page.ABOUT -> aboutPage(context)
        }
    }
}

/** Runs a connection test and shows "OK · 420 ms" or the error. */
@Composable
private fun TestButton(label: String, test: suspend () -> Long) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(
            enabled = !running,
            onClick = {
                running = true
                result = null
                scope.launch {
                    val r = runCatching { test() }
                    running = false
                    result = r.fold({ true to "OK · $it ms" }, { false to (it.message ?: it.toString()) })
                }
            },
        ) { Text(if (running) "Testing…" else label) }
        result?.let { (ok, text) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                maxLines = 6,
            )
        }
    }
}

// --- API keys ---

private fun LazyListScope.keysPage(s: Settings, keys: ApiKeys) {
    val g = WisprApp.graph
    item {
        SectionCard("ElevenLabs Scribe (speech-to-text, default)") {
            SecretRow("ElevenLabs API key", keys.elevenlabs, { v -> updateKeys { it.copy(elevenlabs = v) } }, supporting = "elevenlabs.io → Developers → API keys")
            TestButton("Test ElevenLabs") { ConnectionTest.elevenLabs(g.http, s.transcription.elevenlabs, keys.elevenlabs) }
        }
    }
    item {
        SectionCard("OpenAI (cleanup, command, translation; or speech-to-text)") {
            SecretRow("OpenAI API key", keys.openai, { v -> updateKeys { it.copy(openai = v) } }, supporting = "platform.openai.com → API keys")
            TestButton("Test OpenAI") { ConnectionTest.openAiModels(g.http, s.transcription.openai.baseUrl, keys.openai) }
        }
    }
    item {
        SectionCard("Other endpoints (optional)") {
            Hint("Only needed if the cleanup, command or translation model is not on OpenAI (e.g. Groq, OpenRouter). Empty = the OpenAI key, sent only to OpenAI.")
            SecretRow("Cleanup key", keys.polish, { v -> updateKeys { it.copy(polish = v) } }, placeholder = "Same as OpenAI")
            TestButton("Test cleanup model") { ConnectionTest.chat(g.chat, LlmResolve.polish(s, keys), "Cleanup") }
            SecretRow("Command key", keys.command, { v -> updateKeys { it.copy(command = v) } }, placeholder = "Same as cleanup")
            SecretRow("Translation key", keys.translation, { v -> updateKeys { it.copy(translation = v) } }, placeholder = "Same as cleanup")
            Hint("Keys are encrypted with a key kept in the Android Keystore and never leave the phone except to their service.")
        }
    }
}

// --- Speech-to-text ---

private fun <T> withCurrent(options: List<Pair<T, String>>, current: T): List<Pair<T, String>> =
    if (options.any { it.first == current }) options else options + (current to current.toString())

private fun LazyListScope.transcriptionPage(s: Settings) {
    val t = s.transcription
    item {
        SectionCard("Service") {
            ChoiceRow(
                "Provider", t.provider,
                listOf(Provider.ELEVENLABS to "ElevenLabs Scribe", Provider.OPENAI to "OpenAI"),
                { p -> update { it.copy(transcription = it.transcription.copy(provider = p)) } },
            )
            ChoiceRow(
                "Spoken language", t.language,
                listOf("auto" to "Auto-detect") + Languages.all.map { it.code to it.name },
                { l -> update { it.copy(transcription = it.transcription.copy(language = l)) } },
                supporting = "Auto-detect also handles mixed languages",
            )
            NumberRow(
                "Timeout", t.timeoutMs / 1000, { v -> update { it.copy(transcription = it.transcription.copy(timeoutMs = v * 1000)) } },
                "s", 5L..300L, supporting = "One automatic retry on network errors",
            )
        }
    }
    item {
        SectionCard("ElevenLabs") {
            ChoiceRow(
                "Model", t.elevenlabs.model, withCurrent(listOf("scribe_v2" to "scribe_v2", "scribe_v1" to "scribe_v1"), t.elevenlabs.model),
                { m -> update { it.copy(transcription = it.transcription.copy(elevenlabs = it.transcription.elevenlabs.copy(model = m))) } },
            )
            SwitchRow(
                "Send the dictionary as keyterms", t.elevenlabs.keyterms,
                { v -> update { it.copy(transcription = it.transcription.copy(elevenlabs = it.transcription.elevenlabs.copy(keyterms = v))) } },
                subtitle = "Better spelling of your terms (+\$0.05 per hour of audio)",
            )
            SwitchRow(
                "Remove filler words (no verbatim)", t.elevenlabs.noVerbatim,
                { v -> update { it.copy(transcription = it.transcription.copy(elevenlabs = it.transcription.elevenlabs.copy(noVerbatim = v))) } },
            )
            TextRow(
                "API URL", t.elevenlabs.baseUrl,
                { v -> update { it.copy(transcription = it.transcription.copy(elevenlabs = it.transcription.elevenlabs.copy(baseUrl = v.trim()))) } },
            )
        }
    }
    item {
        SectionCard("OpenAI") {
            ChoiceRow(
                "Model", t.openai.model,
                withCurrent(listOf("gpt-4o-transcribe", "gpt-transcribe", "gpt-4o-mini-transcribe", "whisper-1").map { it to it }, t.openai.model),
                { m -> update { it.copy(transcription = it.transcription.copy(openai = it.transcription.openai.copy(model = m))) } },
            )
            TextRow(
                "Context prompt (optional)", t.openai.prompt,
                { v -> update { it.copy(transcription = it.transcription.copy(openai = it.transcription.openai.copy(prompt = v))) } },
                singleLine = false, minLines = 2, supporting = "The dictionary is appended as \"Vocabulary: …\"",
            )
            TextRow(
                "API URL", t.openai.baseUrl,
                { v -> update { it.copy(transcription = it.transcription.copy(openai = it.transcription.openai.copy(baseUrl = v.trim()))) } },
            )
        }
    }
}

// --- Cleanup ---

private val EFFORTS = listOf("none", "minimal", "low", "medium", "high")

@Composable
private fun TemperatureRows(value: Double?, onChange: (Double?) -> Unit) {
    SwitchRow("Custom temperature", value != null, { on -> onChange(if (on) 0.3 else null) }, subtitle = if (value == null) "Not sent (model default)" else null)
    if (value != null) {
        SliderRow("Temperature", value.toFloat(), 0f..2f, { "%.1f".format(Locale.ROOT, it) }, { onChange((it * 10).toInt() / 10.0) }, steps = 19)
    }
}

private fun LazyListScope.cleanupPage(s: Settings, keys: ApiKeys) {
    val p = s.polish
    val g = WisprApp.graph
    item {
        SectionCard {
            SwitchRow(
                "Clean up dictations with an LLM", p.enabled, { v -> update { it.copy(polish = it.polish.copy(enabled = v)) } },
                subtitle = "Removes filler words and false starts, fixes punctuation. On failure the raw transcript is used.",
            )
        }
    }
    item {
        SectionCard("Model") {
            TextRow("Model", p.model, { v -> update { it.copy(polish = it.polish.copy(model = v.trim())) } }, supporting = "e.g. gpt-6-luna, gpt-5-mini, gpt-4.1-mini")
            TextRow(
                "API URL", p.baseUrl, { v -> update { it.copy(polish = it.polish.copy(baseUrl = v.trim())) } },
                supporting = "Any OpenAI-compatible endpoint (OpenAI, Groq, OpenRouter, Ollama…)",
            )
            ChoiceRow(
                "Reasoning effort", p.reasoningEffort,
                listOf<Pair<String?, String>>(null to "Not sent") + EFFORTS.map { it to it },
                { v -> update { it.copy(polish = it.polish.copy(reasoningEffort = v)) } },
            )
            TemperatureRows(p.temperature) { v -> update { it.copy(polish = it.polish.copy(temperature = v)) } }
            NumberRow("Timeout", p.timeoutMs / 1000, { v -> update { it.copy(polish = it.polish.copy(timeoutMs = v * 1000)) } }, "s", 2L..120L)
            NumberRow(
                "Skip for fewer than", p.minWords.toLong(), { v -> update { it.copy(polish = it.polish.copy(minWords = v.toInt())) } },
                "words", 0L..1000L, supporting = "0 = always clean up",
            )
            TestButton("Test the cleanup model") { ConnectionTest.chat(g.chat, LlmResolve.polish(s, keys), "Cleanup") }
        }
    }
    item {
        SectionCard("Instructions") {
            TextRow("Instructions", p.instructions, { v -> update { it.copy(polish = it.polish.copy(instructions = v)) } }, singleLine = false, minLines = 4)
            TextButton(onClick = { update { it.copy(polish = it.polish.copy(instructions = DEFAULT_POLISH_INSTRUCTIONS)) } }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("Reset to default")
            }
        }
    }
}

// --- Command & translation ---

private const val INHERIT = "__inherit__"

@Composable
private fun LlmOverrideRows(o: LlmOverride, s: Settings, onChange: (LlmOverride) -> Unit) {
    TextRow("Model", o.model ?: "", { v -> onChange(o.copy(model = v.trim().ifEmpty { null })) }, placeholder = "Same as cleanup (${s.polish.model})")
    TextRow("API URL", o.baseUrl ?: "", { v -> onChange(o.copy(baseUrl = v.trim().ifEmpty { null })) }, placeholder = "Same as cleanup")
    ChoiceRow(
        "Reasoning effort", if (o.inheritReasoningEffort) INHERIT else o.reasoningEffort,
        listOf<Pair<String?, String>>(INHERIT to "Same as cleanup (${s.polish.reasoningEffort ?: "not sent"})", null to "Not sent") + EFFORTS.map { it to it },
        { v -> onChange(if (v == INHERIT) o.copy(inheritReasoningEffort = true, reasoningEffort = null) else o.copy(inheritReasoningEffort = false, reasoningEffort = v)) },
    )
    SwitchRow("Same temperature as cleanup", o.inheritTemperature, { v -> onChange(o.copy(inheritTemperature = v, temperature = null)) })
    if (!o.inheritTemperature) TemperatureRows(o.temperature) { v -> onChange(o.copy(temperature = v)) }
}

private fun LazyListScope.commandPage(s: Settings, keys: ApiKeys) {
    val c = s.command
    val g = WisprApp.graph
    item {
        SectionCard {
            SwitchRow(
                "Command mode", c.enabled, { v -> update { it.copy(command = it.command.copy(enabled = v)) } },
                subtitle = "Hold the bubble and slide up, or tap ✨ in hands-free mode, then speak an instruction: it's applied to the selected text (rewrite, translate, shorten…) or writes new text at the cursor.",
            )
        }
    }
    item {
        SectionCard("Model") {
            LlmOverrideRows(c.llm, s) { o -> update { it.copy(command = it.command.copy(llm = o)) } }
            NumberRow("Timeout", c.timeoutMs / 1000, { v -> update { it.copy(command = it.command.copy(timeoutMs = v * 1000)) } }, "s", 5L..180L)
            TestButton("Test the command model") { ConnectionTest.chat(g.chat, LlmResolve.command(s, keys), "Command") }
        }
    }
}

@Composable
private fun AddPairRow() {
    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf("en") }
    val languages = Languages.all.map { it.code to it.name }
    ChoiceRow("From", from, listOf<Pair<String?, String>>(null to "Any language") + languages, { from = it })
    ChoiceRow("To", to, languages, { to = it })
    Button(
        onClick = { update { it.copy(translation = it.translation.copy(pairs = it.translation.pairs + TranslationPairSetting(from, to))) } },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Icon(Icons.Rounded.Add, null)
        Text("Add this pair")
    }
}

private fun LazyListScope.translationPage(s: Settings, keys: ApiKeys) {
    val t = s.translation
    val pairs = TranslationPairs.build(t.pairs).first
    val g = WisprApp.graph
    item {
        SectionCard("Language pairs") {
            Hint("While a pair is active, dictations are translated. Switch from Home or the Quick Settings tile; the bubble shows the target language.")
            ChoiceRow(
                "Active", t.active?.takeIf { id -> pairs.any { it.id == id } },
                listOf<Pair<String?, String>>(null to "Off") + pairs.map { it.id to it.label },
                { v -> update { it.copy(translation = it.translation.copy(active = v)) } },
            )
            t.pairs.forEachIndexed { i, pair ->
                val label = TranslationPairs.build(listOf(pair)).first.firstOrNull()?.label ?: "${pair.from ?: "any"} → ${pair.to} (invalid)"
                ListItem(
                    headlineContent = { Text(label) },
                    trailingContent = {
                        IconButton(onClick = {
                            update { it.copy(translation = it.translation.copy(pairs = it.translation.pairs.filterIndexed { j, _ -> j != i })) }
                        }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
                    },
                )
            }
        }
    }
    item { SectionCard("Add a pair") { AddPairRow() } }
    item {
        SectionCard("Model") {
            LlmOverrideRows(t.llm, s) { o -> update { it.copy(translation = it.translation.copy(llm = o)) } }
            NumberRow("Timeout", t.timeoutMs / 1000, { v -> update { it.copy(translation = it.translation.copy(timeoutMs = v * 1000)) } }, "s", 5L..120L)
            TestButton("Test the translation model") { ConnectionTest.chat(g.chat, LlmResolve.translation(s, keys), "Translation") }
        }
    }
}

// --- Bubble ---

private fun launcherApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .distinctBy { it.first }
        .filter { it.first != context.packageName }
        .sortedBy { it.second.lowercase() }
}

@Composable
private fun ExcludedApps(s: Settings, context: Context) {
    var picking by remember { mutableStateOf(false) }
    s.bubble.excludedApps.forEach { pkg ->
        ListItem(
            headlineContent = { Text(appLabel(context, pkg)) },
            supportingContent = { Text(pkg) },
            trailingContent = {
                IconButton(onClick = { update { it.copy(bubble = it.bubble.copy(excludedApps = it.bubble.excludedApps - pkg)) } }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                }
            },
        )
    }
    TextButton(onClick = { picking = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add an app") }
    if (picking) {
        val apps = remember { launcherApps(context) }
        var filter by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Hide the bubble in…") },
            text = {
                Column {
                    OutlinedTextField(value = filter, onValueChange = { filter = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(apps.filter { it.second.contains(filter, ignoreCase = true) }, key = { it.first }) { (pkg, label) ->
                            Text(
                                label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        update { it.copy(bubble = it.bubble.copy(excludedApps = (it.bubble.excludedApps + pkg).distinct())) }
                                        picking = false
                                    }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
        )
    }
}

private fun LazyListScope.bubblePage(s: Settings, context: Context) {
    val b = s.bubble
    fun set(transform: (io.github.hexalyse.wisprcheap.core.settings.BubbleSettings) -> io.github.hexalyse.wisprcheap.core.settings.BubbleSettings) =
        update { it.copy(bubble = transform(it.bubble)) }
    item {
        SectionCard("Appearance") {
            ChoiceRow(
                "Show the bubble", b.showWhen,
                listOf(
                    ShowWhen.EDITING to "While typing (field focused + keyboard open)",
                    ShowWhen.EDITOR_ACTIVE to "When a text field is focused",
                    ShowWhen.KEYBOARD_VISIBLE to "When the keyboard is open",
                    ShowWhen.ALWAYS to "Always",
                ),
                { v -> set { it.copy(showWhen = v) } },
            )
            ChoiceRow("Size", b.size, listOf(BubbleSize.S to "Small", BubbleSize.M to "Medium", BubbleSize.L to "Large"), { v -> set { it.copy(size = v) } })
            SliderRow("Opacity when idle", b.idleOpacity, 0.3f..1f, { "${(it * 100).toInt()} %" }, { v -> set { it.copy(idleOpacity = v) } })
            SwitchRow("Follow the keyboard", b.followKeyboard, { v -> set { it.copy(followKeyboard = v) } }, subtitle = "The bubble keeps its distance above the keyboard")
            SwitchRow("Hide on password fields", b.hideOnPasswordFields, { v -> set { it.copy(hideOnPasswordFields = v) } })
            TextButton(
                onClick = { context.getSharedPreferences("bubble", Context.MODE_PRIVATE).edit().clear().apply() },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Reset the bubble position") }
        }
    }
    item {
        SectionCard("Gestures") {
            NumberRow("Hold before recording starts", b.micStartDelayMs, { v -> set { it.copy(micStartDelayMs = v) } }, "ms", 0L..1000L, supporting = "Moving before that drags the bubble")
            NumberRow("Tap if released within", b.tapMaxMs, { v -> set { it.copy(tapMaxMs = v) } }, "ms", 100L..1500L, supporting = "A tap records hands-free")
            NumberRow("Slide up for a command", b.commandSlideDp.toLong(), { v -> set { it.copy(commandSlideDp = v.toInt()) } }, "dp", 24L..240L)
            NumberRow("Slide away to cancel", b.cancelSlideDp.toLong(), { v -> set { it.copy(cancelSlideDp = v.toInt()) } }, "dp", 40L..320L)
        }
    }
    item {
        SectionCard("Feedback") {
            SwitchRow("Haptics", b.haptics, { v -> set { it.copy(haptics = v) } }, subtitle = "Short vibrations when recording starts, stops or is cancelled")
            SwitchRow("Vibrate when the text is inserted", b.hapticOnInsert, { v -> set { it.copy(hapticOnInsert = v) } })
            SwitchRow("Keep the screen on while recording", b.keepScreenOnWhileRecording, { v -> set { it.copy(keepScreenOnWhileRecording = v) } })
        }
    }
    item { SectionCard("Excluded apps") { ExcludedApps(s, context) } }
}

// --- Insertion ---

private fun LazyListScope.insertionPage(s: Settings, context: Context) {
    val o = s.output
    item {
        SectionCard {
            ChoiceRow(
                "Insertion method", o.insertMethod,
                listOf(
                    InsertMethod.AUTO to "Automatic (recommended)",
                    InsertMethod.INPUT_CONNECTION to "Like a keyboard (commitText)",
                    InsertMethod.SET_TEXT to "Set the field's text",
                    InsertMethod.PASTE to "Paste through the clipboard",
                    InsertMethod.CLIPBOARD to "Clipboard only",
                ),
                { v -> update { it.copy(output = it.output.copy(insertMethod = v)) } },
                supporting = "Automatic tries each method in turn and remembers what works per app",
            )
            SwitchRow("Smart spacing", o.smartSpacing, { v -> update { it.copy(output = it.output.copy(smartSpacing = v)) } }, subtitle = "Adds a space before or after the text when needed")
            SwitchRow("Trailing space", o.trailingSpace, { v -> update { it.copy(output = it.output.copy(trailingSpace = v)) } }, subtitle = "After a dictation, so the next one starts cleanly")
            SwitchRow(
                "Hide the clipboard preview", o.hideClipboardPreview, { v -> update { it.copy(output = it.output.copy(hideClipboardPreview = v)) } },
                subtitle = "When the clipboard is used, Android shows \"Copied\" without the text",
            )
        }
    }
    item {
        SectionCard("Methods learned per app") {
            if (o.perApp.isEmpty()) Hint("None: the default method works everywhere so far.")
            o.perApp.forEach { (pkg, method) ->
                ListItem(
                    headlineContent = { Text(appLabel(context, pkg)) },
                    supportingContent = { Text(method.name.lowercase().replace('_', ' ')) },
                    trailingContent = {
                        IconButton(onClick = { update { it.copy(output = it.output.copy(perApp = it.output.perApp - pkg)) } }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Forget")
                        }
                    },
                )
            }
        }
    }
}

// --- Recording ---

@Composable
private fun MicTest(threshold: Double, source: AudioSourceSetting) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = !running,
                onClick = {
                    running = true
                    result = null
                    thread(name = "wc-mic-test") {
                        val mic = MicSession(context.applicationContext, source) { level = it }
                        val started = mic.start(SystemClock.elapsedRealtime())
                        if (started) Thread.sleep(3000)
                        val r = mic.stop()
                        val pcm = mic.pcm()
                        val peak = Pcm.loudestWindowDb(pcm)
                        result = when {
                            r.error != null -> "Failed: ${r.error}"
                            Pcm.isAllZero(pcm) -> "Silence only: Android silenced the microphone"
                            else -> "Loudest: %.1f dBFS (threshold %.0f): %s".format(
                                Locale.ROOT, peak, threshold, if (peak >= threshold) "speech would be kept" else "would be discarded as silence",
                            )
                        }
                        level = 0f
                        running = false
                    }
                },
            ) { Text(if (running) "Speak now…" else "Test the microphone (3 s)") }
        }
        if (running) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun LazyListScope.recordingPage(s: Settings) {
    val r = s.recording
    item {
        SectionCard {
            NumberRow("Minimum duration", r.minDurationMs, { v -> update { it.copy(recording = it.recording.copy(minDurationMs = v)) } }, "ms", 0L..5000L, supporting = "Shorter recordings are dropped")
            NumberRow("Keep recording after release", r.tailMs, { v -> update { it.copy(recording = it.recording.copy(tailMs = v)) } }, "ms", 0L..1000L, supporting = "So the last word isn't cut")
            NumberRow("Maximum duration", r.maxDurationSec.toLong(), { v -> update { it.copy(recording = it.recording.copy(maxDurationSec = v.toDouble())) } }, "s", 10L..3600L, supporting = "Stops and sends automatically")
            SliderRow(
                "Silence threshold", r.silenceThresholdDb.toFloat(), -90f..-20f, { "%.0f dBFS".format(Locale.ROOT, it) },
                { v -> update { it.copy(recording = it.recording.copy(silenceThresholdDb = Math.round(v).toDouble())) } },
                supporting = "Recordings whose loudest moment is quieter than this are dropped without being sent",
            )
            MicTest(r.silenceThresholdDb, r.audioSource)
            ChoiceRow(
                "Audio source", r.audioSource,
                listOf(
                    AudioSourceSetting.VOICE_RECOGNITION to "Voice recognition (recommended)",
                    AudioSourceSetting.MIC to "Microphone",
                    AudioSourceSetting.UNPROCESSED to "Unprocessed",
                ),
                { v -> update { it.copy(recording = it.recording.copy(audioSource = v)) } },
            )
        }
    }
}

// --- History, privacy, notifications ---

private fun LazyListScope.historyPage(s: Settings) {
    val h = s.history
    fun set(transform: (io.github.hexalyse.wisprcheap.core.settings.HistorySettings) -> io.github.hexalyse.wisprcheap.core.settings.HistorySettings) =
        update { it.copy(history = transform(it.history)) }
    item {
        SectionCard("History") {
            SwitchRow("Keep a history", h.enabled, { v -> set { it.copy(enabled = v) } }, subtitle = "Stored on the phone only, in the desktop history.jsonl format")
            SwitchRow("Record the target app", h.recordTargetApp, { v -> set { it.copy(recordTargetApp = v) } })
            SwitchRow("Save failed recordings", h.saveFailedAudio, { v -> set { it.copy(saveFailedAudio = v) } }, subtitle = "So they can be retried later")
            NumberRow("Delete saved recordings after", h.failedAudioRetentionDays.toLong(), { v -> set { it.copy(failedAudioRetentionDays = v.toInt()) } }, "days", 0L..3650L, supporting = "0 = keep forever")
        }
    }
    item {
        SectionCard("Log and notifications") {
            SwitchRow("Write the dictated text in the log", h.logDictatedText, { v -> set { it.copy(logDictatedText = v) } })
            SwitchRow(
                "Error notifications", s.notifications.errors, { v -> update { it.copy(notifications = it.notifications.copy(errors = v)) } },
                subtitle = "Failed transcription (with Retry), translation, command, microphone",
            )
        }
    }
}

// --- Prices ---

private fun LazyListScope.pricingPage(s: Settings) {
    item {
        SectionCard("Known prices") {
            Text(
                buildString {
                    appendLine("Speech-to-text ($/min)")
                    Pricing.TRANSCRIPTION_PER_MINUTE.forEach { (m, p) -> appendLine("  %-24s %.5f".format(Locale.ROOT, m, p)) }
                    appendLine("  + Scribe keyterms       %.5f".format(Locale.ROOT, Pricing.SCRIBE_KEYTERMS_PER_MINUTE))
                    appendLine()
                    appendLine("LLM ($ per 1M tokens: in / out)")
                    Pricing.LLM_PER_MILLION.forEach { (m, p) -> appendLine("  %-16s %.2f / %.2f".format(Locale.ROOT, m, p.first, p.second)) }
                }.trimEnd(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    item {
        SectionCard("Custom prices") {
            Hint("For models that aren't in the table (or new prices). Speech-to-text: $ per minute; LLM: $ per million tokens.")
            s.pricing.overrides.forEach { (model, o) ->
                ListItem(
                    headlineContent = { Text(model) },
                    supportingContent = { Text(o.perMinute?.let { "$it $/min" } ?: "${o.inputPerM} / ${o.outputPerM} $ per 1M tokens") },
                    trailingContent = {
                        IconButton(onClick = { update { it.copy(pricing = it.pricing.copy(overrides = it.pricing.overrides - model)) } }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                        }
                    },
                )
            }
            AddPriceRow()
        }
    }
}

@Composable
private fun AddPriceRow() {
    var model by remember { mutableStateOf("") }
    var llm by remember { mutableStateOf(true) }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    ChoiceRow("Kind", llm, listOf(true to "LLM (per 1M tokens)", false to "Speech-to-text (per minute)"), { llm = it })
    TextRow("Model", model, { model = it })
    TextRow(if (llm) "Input price" else "Price per minute", a, { a = it }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    if (llm) TextRow("Output price", b, { b = it }, keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    val first = a.replace(',', '.').toDoubleOrNull()
    val second = b.replace(',', '.').toDoubleOrNull()
    Button(
        enabled = model.isNotBlank() && first != null && (!llm || second != null),
        onClick = {
            val o = if (llm) PriceOverride(inputPerM = first, outputPerM = second) else PriceOverride(perMinute = first)
            update { it.copy(pricing = it.pricing.copy(overrides = it.pricing.overrides + (model.trim() to o))) }
            model = ""
            a = ""
            b = ""
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    ) { Text("Add") }
}

// --- About ---

private fun LazyListScope.aboutPage(context: Context) {
    item {
        val editor by WisprApp.graph.state.editor.collectAsStateWithLifecycle()
        SectionCard("WisprCheap ${BuildConfig.VERSION_NAME}") {
            Hint("Push-to-talk dictation for any app: speech-to-text, LLM cleanup, commands and translation. Android port of the wisprcheap desktop app.")
            Text(
                """
                service connected: ${editor.serviceConnected}
                text field active: ${editor.inputStarted} (${editor.editorPackage ?: "-"})
                password field:    ${editor.isPassword}
                keyboard visible:  ${editor.imeVisible} ${editor.imeBounds ?: ""}
                focused editable:  ${editor.focusedEditable}
                foreground app:    ${editor.activePackage ?: "-"}
                """.trimIndent(),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { SystemScreens.accessibility(context) }) { Text("Accessibility settings") }
                TextButton(onClick = { SystemScreens.appInfo(context) }) { Text("App info") }
            }
        }
    }
    item {
        SectionCard("Stored on this phone") {
            Hint("Settings, encrypted API keys, history.jsonl, saved failed recordings and the log, all in the app's private storage and excluded from backups. Audio goes only to the speech-to-text service you chose; text only to the LLM endpoints you configured.")
        }
    }
}
