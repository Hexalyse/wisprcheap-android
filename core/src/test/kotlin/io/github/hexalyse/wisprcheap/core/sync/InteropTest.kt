package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.history.Costs
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.Timestamps
import io.github.hexalyse.wisprcheap.core.history.TranscriptionInfo
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Against a real sync server (manual interop check with the desktop app, skipped by default):
 * `WCTEST_SERVER=http://127.0.0.1:18080 WCTEST_CODE=ABCD2345 WCTEST_PASSPHRASE=… WCTEST_STATE=<dir> gradlew :core:test`.
 * Expects the desktop to have paired first (polish.model gpt-5-mini, term Kubernetes, OpenAI key sk-desktop);
 * adds the term "FromAndroid" and one history entry.
 */
class InteropTest {
    @Test
    fun withRealServer() = runTest {
        val server = System.getenv("WCTEST_SERVER") ?: return@runTest
        val dir = File(System.getenv("WCTEST_STATE")!!).also { it.mkdirs() }
        val http = OkHttpClient()
        val host = object : SyncHost {
            override var settings = Settings(dictionary = listOf(DictionaryEntry("Bun")))
            override var keys = ApiKeys()
            override var history = listOf(
                HistoryEntry(
                    ts = Timestamps.iso(Instant.now()), id = UUID.randomUUID().toString(), durationSec = 4.0,
                    transcription = TranscriptionInfo("elevenlabs", "scribe_v2", 700, 2), raw = "hello from android",
                    text = "Hello from Android.", words = 3, costUsd = Costs(0.0003, null, 0.0003),
                ),
            )

            override suspend fun write(settings: Settings, keys: ApiKeys) {
                this.settings = settings
                this.keys = keys
            }

            override suspend fun addHistory(entries: List<HistoryEntry>) {
                history = history + entries
            }

            override fun loadState() = File(dir, "state.json").takeIf { it.exists() }?.let { SyncState.decode(it.readText()) } ?: SyncState()

            override fun saveState(state: SyncState) = File(dir, "state.json").writeText(state.encode())
        }
        val paired = SyncApi.pair(http, server, PairRequest(System.getenv("WCTEST_CODE")!!, "Interop phone", "android", "test"))
        val me = SyncApi(http, server, paired.token).me()
        val dk = SyncEngine.unlock(me.user.id, me.keyring!!, System.getenv("WCTEST_PASSPHRASE")!!)
        val engine = SyncEngine(http, host) { println(it) }
        val creds = SyncCredentials(server, paired.token, dk)
        val first = engine.run(creds, SyncOptions(downloadHistory = true))
        println("first: $first")
        assertEquals("gpt-5-mini", host.settings.polish.model)
        assertEquals("sk-desktop", host.keys.openai)
        assertTrue(host.settings.dictionary.any { it.term == "Kubernetes" }, host.settings.dictionary.toString())
        assertTrue(host.history.any { it.text == "Desktop entry." }, host.history.toString())
        host.settings = host.settings.copy(dictionary = host.settings.dictionary + DictionaryEntry("FromAndroid", listOf("from android")))
        host.keys = host.keys.copy(elevenlabs = "el-android")
        val second = engine.run(creds, SyncOptions(downloadHistory = true))
        println("second: $second")
        assertEquals(1, second.pushed["dict"])
        assertEquals(1, second.pushed["secret"])
        val idle = engine.run(creds, SyncOptions(downloadHistory = true))
        assertTrue(idle.pushed.isEmpty() && idle.applied.isEmpty(), idle.toString())
        println("month: ${idle.month}")
    }
}
