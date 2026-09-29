package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.history.Costs
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.PolishInfo
import io.github.hexalyse.wisprcheap.core.history.Timestamps
import io.github.hexalyse.wisprcheap.core.history.TranscriptionInfo
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.DictionaryEntry
import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Just enough of the real server (`server/src/api.rs`) to exercise the engine: LWW by HLC, history, stats. */
private class FakeServer : Dispatcher() {
    val tokens = mutableMapOf<String, Pair<String, String>>() // token → (user, device)
    var keyring: Keyring? = null
    val records = linkedMapOf<String, Change>()
    var seq = 0L

    fun device(name: String): String {
        val id = "dev_${name}-X"
        tokens["wcs_$name"] = "usr_1" to id
        return id
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")
        val (user, device) = tokens[token] ?: return json("""{"error":"unauthorized","message":"no"}""", 401)
        val url = request.url
        val body = request.body?.utf8().orEmpty()
        return when ("${request.method} ${url.encodedPath}") {
            "GET /v1/me" -> json(
                SyncJson.encodeToString(MeResponse(UserInfo(user, "alice"), DeviceInfo(device, device, "android"), keyring = keyring)),
            )
            "PUT /v1/keyring" -> {
                if (keyring != null) return json("""{"error":"keyring_exists","message":"exists"}""", 409)
                val req = SyncJson.decodeFromString<PutKeyringRequest>(body)
                keyring = Keyring(1, req.keyId, req.salt, req.kdf, req.wrappedKey)
                json("""{"keyVersion":1}""")
            }
            "GET /v1/changes" -> {
                val since = url.queryParameter("since")!!.toLong()
                val limit = url.queryParameter("limit")!!.toInt()
                val exclude = url.queryParameter("exclude") == "history"
                val all = records.values.filter { it.seq!! > since && !(exclude && it.kind == "history") }.sortedBy { it.seq }
                val page = all.take(limit)
                json(SyncJson.encodeToString(ChangesResponse(page, page.lastOrNull()?.seq ?: since, all.size > limit)))
            }
            "POST /v1/changes" -> {
                val req = SyncJson.decodeFromString<PushRequest>(body)
                val results = req.changes.map { c ->
                    val existing = records[c.key]
                    when {
                        Hlc.parse(c.hlc) == null -> PushResult(c.kind, c.id, PushStatus.REJECTED, error = "invalid_hlc")
                        c.kind == "history" && existing != null && (!c.deleted || existing.deleted) ->
                            PushResult(c.kind, c.id, PushStatus.EXISTS, existing.seq)
                        c.kind != "history" && existing != null && hlcGe(existing.hlc, c.hlc) ->
                            PushResult(c.kind, c.id, PushStatus.STALE, current = existing)
                        else -> {
                            seq++
                            records[c.key] = c.copy(seq = seq, device = device, payload = if (c.deleted) null else c.payload)
                            PushResult(c.kind, c.id, PushStatus.APPLIED, seq)
                        }
                    }
                }
                json(SyncJson.encodeToString(PushResponse(results)))
            }
            "GET /v1/stats" -> {
                val month = url.queryParameter("from")!!
                val entries = records.values.filter { it.kind == "history" && !it.deleted }
                val stats = MonthStats(month, entries = entries.size.toLong(), words = entries.sumOf { it.stats?.words ?: 0 })
                json(SyncJson.encodeToString(StatsResponse(listOf(stats), tokens.values.map { DeviceInfo(it.second, it.second) })))
            }
            else -> json("""{"error":"not_found","message":"${request.method} $url"}""", 404)
        }
    }
}

private class MemoryHost(override var settings: Settings, override var keys: ApiKeys = ApiKeys()) : SyncHost {
    override var history: List<HistoryEntry> = emptyList()
    var state = SyncState()
    var writes = 0

    override suspend fun write(settings: Settings, keys: ApiKeys) {
        this.settings = settings
        this.keys = keys
        writes++
    }

    override suspend fun addHistory(entries: List<HistoryEntry>) {
        history = history + entries
    }

    override fun loadState() = SyncState.decode(state.encode())

    override fun saveState(state: SyncState) {
        this.state = SyncState.decode(state.encode())
    }
}

class SyncEngineTest {
    private val fake = FakeServer()
    private val server = MockWebServer().also {
        it.dispatcher = fake
        it.start()
    }
    private val url = server.url("/").toString().removeSuffix("/")
    private val http = OkHttpClient()
    private val fastKdf = KdfParams(m = 8192, t = 1, p = 1)

    @AfterTest
    fun close() = server.close()

    private fun entry(words: Int, device: String? = null, id: String? = null) = HistoryEntry(
        ts = Timestamps.iso(Instant.now()),
        id = id,
        device = device,
        durationSec = 2.0,
        transcription = TranscriptionInfo("elevenlabs", "scribe_v2", 500, 1),
        polish = PolishInfo("gpt-6-luna", 300, 100, 10),
        raw = "secret",
        text = "Secret.",
        words = words,
        costUsd = Costs(0.0001, 0.00001, 0.00011),
    )

    @Test
    fun twoPhonesConverge() = runTest {
        val devA = fake.device("a")
        val devB = fake.device("b")
        val a = MemoryHost(
            Settings(dictionary = listOf(DictionaryEntry("Kubernetes"))).let { it.copy(polish = it.polish.copy(model = "gpt-6-sol")) },
            ApiKeys(openai = "sk-a"),
        )
        a.history = listOf(entry(3), entry(4, id = "0b8d7a5e-3c1f-4c2a-9d4e-2f1a6b7c8d9e"))
        val b = MemoryHost(Settings(dictionary = listOf(DictionaryEntry("Bun"))))
        val engineA = SyncEngine(http, a)
        val engineB = SyncEngine(http, b)

        // A creates the keyring (what the pairing screen does), then syncs.
        val dk = DataKey.generate()
        SyncApi(http, url, "wcs_a").putKeyring(SyncEngine.keyringRequest("usr_1", dk, "correct horse", fastKdf))
        val credsA = SyncCredentials(url, "wcs_a", dk)
        val first = engineA.run(credsA, SyncOptions())
        assertTrue(first.first)
        assertEquals(28, first.pushed["setting"])
        assertEquals(5, first.pushed["secret"])
        assertEquals(1, first.pushed["dict"])
        assertEquals(2, first.uploaded)
        assertEquals(2L, first.month?.entries)

        // B unlocks with the passphrase: the server wins, B's own term is uploaded.
        val keyring = SyncApi(http, url, "wcs_b").me().keyring!!
        val dkB = SyncEngine.unlock("usr_1", keyring, "correct horse")
        assertFailsWith<WrongPassphraseException> { SyncEngine.unlock("usr_1", keyring, "wrong horse") }
        val credsB = SyncCredentials(url, "wcs_b", dkB)
        val firstB = engineB.run(credsB, SyncOptions(downloadHistory = true))
        assertEquals(1, firstB.pushed["dict"])
        assertEquals("gpt-6-sol", b.settings.polish.model)
        assertEquals("sk-a", b.keys.openai)
        assertEquals(listOf("Bun", "Kubernetes"), b.settings.dictionary.map { it.term })
        assertEquals(2, firstB.downloaded)
        assertTrue(b.history.all { it.device == devA })

        // A gets B's term; then nothing moves anymore.
        engineA.run(credsA, SyncOptions())
        assertEquals(listOf("Kubernetes", "Bun"), a.settings.dictionary.map { it.term })
        val idleA = engineA.run(credsA, SyncOptions())
        val idleB = engineB.run(credsB, SyncOptions(downloadHistory = true))
        assertTrue(idleA.pushed.isEmpty() && idleA.applied.isEmpty(), idleA.toString())
        assertTrue(idleB.pushed.isEmpty() && idleB.applied.isEmpty(), idleB.toString())

        // Edits on B (settings, keys, a removed term) reach A.
        b.settings = b.settings.copy(dictionary = listOf(DictionaryEntry("Bun")), polish = b.settings.polish.copy(temperature = 0.3))
        b.keys = b.keys.copy(elevenlabs = "el-b")
        val sent = engineB.run(credsB, SyncOptions(downloadHistory = true))
        assertEquals(1, sent.pushed["dict"])
        engineA.run(credsA, SyncOptions())
        assertEquals(listOf("Bun"), a.settings.dictionary.map { it.term })
        assertEquals(0.3, a.settings.polish.temperature)
        assertEquals("el-b", a.keys.elevenlabs)

        // New history on B is uploaded once; a local deletion becomes a tombstone.
        val mine = entry(5, device = devB, id = "5f0c2a8e-1b3d-4e6f-8a9b-0c1d2e3f4a5b")
        b.history = b.history + mine
        assertEquals(1, engineB.run(credsB, SyncOptions(downloadHistory = true)).uploaded)
        assertEquals(0, engineB.run(credsB, SyncOptions(downloadHistory = true)).uploaded)
        b.history = b.history - mine
        engineB.historyDeleted(listOf(mine))
        val afterDelete = engineB.run(credsB, SyncOptions(downloadHistory = true))
        assertEquals(2L, afterDelete.month?.entries)
        assertTrue(fake.records["history/${mine.id}"]!!.deleted)
    }

    @Test
    fun revokedTokenAndResetKey() = runTest {
        fake.device("a")
        val host = MemoryHost(Settings())
        val engine = SyncEngine(http, host)
        val dk = DataKey.generate()
        assertFailsWith<NeedsKeyException> { engine.run(SyncCredentials(url, "wcs_a", dk), SyncOptions()) }
        SyncApi(http, url, "wcs_a").putKeyring(SyncEngine.keyringRequest("usr_1", DataKey.generate(), "passphrase", fastKdf))
        assertFailsWith<NeedsKeyException> { engine.run(SyncCredentials(url, "wcs_a", dk), SyncOptions()) }
        assertFailsWith<SyncUnauthorizedException> { engine.run(SyncCredentials(url, "wcs_revoked", dk), SyncOptions()) }
    }
}
