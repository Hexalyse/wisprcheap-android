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
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
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
    var failPullAt: Long? = null
    var failPush = false
    var malformedAcknowledgements = false
    val reports = mutableListOf<SyncReportRequest>()
    val pushBytes = mutableListOf<Int>()

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
                SyncJson.encodeToString(MeResponse(UserInfo(user, "alice"), DeviceInfo(device, device, "android"), keyring = keyring, capabilities = Capabilities(syncReport = true))),
            )
            "PUT /v1/keyring" -> {
                if (keyring != null) return json("""{"error":"keyring_exists","message":"exists"}""", 409)
                val req = SyncJson.decodeFromString<PutKeyringRequest>(body)
                keyring = Keyring(1, req.keyId, req.salt, req.kdf, req.wrappedKey)
                json("""{"keyVersion":1}""")
            }
            "GET /v1/changes" -> {
                val since = url.queryParameter("since")!!.toLong()
                if (failPullAt?.let { since >= it } == true) return json("""{"error":"temporary","message":"try again"}""", 503)
                val limit = url.queryParameter("limit")!!.toInt()
                val exclude = url.queryParameter("exclude") == "history"
                val all = records.values.filter { it.seq!! > since && !(exclude && it.kind == "history") }.sortedBy { it.seq }
                val page = all.take(limit)
                json(SyncJson.encodeToString(ChangesResponse(page, page.lastOrNull()?.seq ?: since, all.size > limit)))
            }
            "POST /v1/changes" -> {
                pushBytes += body.toByteArray(Charsets.UTF_8).size
                if (body.toByteArray(Charsets.UTF_8).size > 1024 * 1024) return json("{}", 413)
                if (failPush) return json("""{"error":"temporary","message":"try again"}""", 503)
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
                json(SyncJson.encodeToString(PushResponse(if (malformedAcknowledgements) results.dropLast(1) else results)))
            }
            "POST /v1/sync-complete" -> {
                reports += SyncJson.decodeFromString<SyncReportRequest>(body)
                MockResponse.Builder().code(204).build()
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
    var failHistory = false
    var failWrite = false

    override suspend fun write(settings: Settings, keys: ApiKeys) {
        if (failWrite) throw IOException("profile write interrupted")
        this.settings = settings
        this.keys = keys
        writes++
    }

    override suspend fun addHistory(entries: List<HistoryEntry>) {
        if (failHistory) throw IOException("history write interrupted")
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

    private suspend fun ready(): Triple<MemoryHost, DataKey, SyncCredentials> {
        fake.device("a")
        val host = MemoryHost(Settings())
        val key = DataKey.generate()
        val creds = SyncCredentials(url, "wcs_a", key, "test-version")
        SyncApi(http, url, "wcs_a").putKeyring(SyncEngine.keyringRequest("usr_1", key, "correct horse", fastKdf))
        SyncEngine(http, host).run(creds, SyncOptions())
        SyncEngine(http, host).run(creds, SyncOptions()) // Acknowledge the profile uploaded on the first run.
        return Triple(host, key, creds)
    }

    @Test
    fun fetchedProfileSurvivesFailureOnTheNextPage() = runTest {
        val (host, key, creds) = ready()
        val base = fake.seq
        repeat(501) { index ->
            val id = if (index == 0) "polish.model" else "unused-$index"
            val c = Change(seq = ++fake.seq, kind = "setting", id = id, hlc = Hlc(Instant.now().toEpochMilli(), index, "dev_b-X").toString(),
                payload = key.encryptRecord("usr_1", "setting", id, JsonPrimitive("recovered-model")), device = "dev_b-X")
            fake.records[c.key] = c
        }
        fake.failPullAt = base + 500
        assertFailsWith<SyncApiException> { SyncEngine(http, host).run(creds, SyncOptions()) }
        assertTrue(host.state.pending.any { it.id == "polish.model" })
        assertEquals(base + 500, host.state.cursor)
        fake.failPullAt = null
        SyncEngine(http, host).run(creds, SyncOptions())
        assertEquals("recovered-model", host.settings.polish.model)
        assertTrue(host.state.pending.isEmpty())
        assertEquals("test-version", fake.reports.last().appVersion)
    }

    @Test
    fun failedProfileWriteKeepsEncryptedInbox() = runTest {
        val (host, key, creds) = ready()
        val id = "polish.model"
        val c = Change(seq = ++fake.seq, kind = "setting", id = id, hlc = Hlc(Instant.now().toEpochMilli(), 0, "dev_b-X").toString(),
            payload = key.encryptRecord("usr_1", "setting", id, JsonPrimitive("after-retry")), device = "dev_b-X")
        fake.records[c.key] = c
        host.failWrite = true
        assertFailsWith<IOException> { SyncEngine(http, host).run(creds, SyncOptions()) }
        assertTrue(host.state.pending.any { it.id == id })
        host.failWrite = false
        SyncEngine(http, host).run(creds, SyncOptions())
        assertEquals("after-retry", host.settings.polish.model)
        assertTrue(host.state.pending.isEmpty())
    }

    @Test
    fun failedHistoryWriteAndLaterUploadCannotLoseOrDuplicateDownloads() = runTest {
        val (host, key, creds) = ready()
        val remote = entry(4, "dev_b-X", "8fe1cf0f-3322-4a1d-817e-43b70206418e")
        val id = remote.id!!
        val json = io.github.hexalyse.wisprcheap.core.history.HistoryJson.encode(remote)
        val c = Change(seq = ++fake.seq, kind = "history", id = id, hlc = Hlc(Instant.now().toEpochMilli(), 0, "dev_b-X").toString(),
            payload = key.encryptRecord("usr_1", "history", id, json), device = "dev_b-X", stats = HistoryStats.fromEntry(json))
        fake.records[c.key] = c
        host.failHistory = true
        assertFailsWith<IOException> { SyncEngine(http, host).run(creds, SyncOptions(downloadHistory = true)) }
        assertTrue(host.state.historyInbox.any { it.id == id })
        host.failHistory = false
        host.history = listOf(entry(2))
        fake.failPush = true
        assertFailsWith<SyncApiException> { SyncEngine(http, host).run(creds, SyncOptions(downloadHistory = true)) }
        assertEquals(1, host.history.count { it.id == id })
        fake.failPush = false
        SyncEngine(http, host).run(creds, SyncOptions(downloadHistory = true))
        assertEquals(1, host.history.count { it.id == id })
        assertTrue(host.state.historyInbox.isEmpty())
    }

    @Test
    fun malformedAcknowledgementsLeaveTheOutboxRetryable() = runTest {
        val (host, _, creds) = ready()
        host.settings = host.settings.copy(polish = host.settings.polish.copy(model = "changed"))
        fake.malformedAcknowledgements = true
        assertFailsWith<SyncApiException> { SyncEngine(http, host).run(creds, SyncOptions()) }
        assertTrue(host.state.outbox.any { it.id == "polish.model" })
        fake.malformedAcknowledgements = false
        SyncEngine(http, host).run(creds, SyncOptions())
        assertTrue(host.state.outbox.isEmpty())
    }

    @Test
    fun undecryptableChangeStaysPendingUntilReplaced() = runTest {
        val (host, key, creds) = ready()
        val previousSuccess = "2000-01-01T00:00:00Z"
        host.state.lastSync = previousSuccess
        val id = "polish.model"
        val broken = Change(seq = ++fake.seq, kind = "setting", id = id,
            hlc = Hlc(Instant.now().toEpochMilli(), 0, "dev_b-X").toString(),
            payload = DataKey.generate().encryptRecord("usr_1", "setting", id, JsonPrimitive("repaired")), device = "dev_b-X")
        fake.records[broken.key] = broken
        val outcome = SyncEngine(http, host).run(creds, SyncOptions())
        assertEquals(1, outcome.pending)
        assertTrue(outcome.summary().contains("waiting to sync"))
        assertEquals(previousSuccess, host.state.lastSync)
        assertEquals(1L, fake.reports.last().pending)
        assertTrue(host.state.outbox.none { it.id == id })

        fake.records[broken.key] = broken.copy(seq = ++fake.seq,
            hlc = Hlc(Instant.now().toEpochMilli(), 1, "dev_b-X").toString(),
            payload = key.encryptRecord("usr_1", "setting", id, JsonPrimitive("repaired")))
        val repaired = SyncEngine(http, host).run(creds, SyncOptions())
        assertEquals(0, repaired.pending)
        assertEquals("repaired", host.settings.polish.model)
        assertTrue(host.state.lastSync != previousSuccess)
        assertEquals(0L, fake.reports.last().pending)
    }

    @Test
    fun oversizedOutboxDoesNotBlockOtherUploadsOrClaimSuccess() = runTest {
        val (host, _, creds) = ready()
        val previousSuccess = "2000-01-01T00:00:00Z"
        host.state.lastSync = previousSuccess
        host.state.outbox += Change(kind = "setting", id = "oversized", hlc = "0", payload = "x".repeat(70_000))
        host.settings = host.settings.copy(polish = host.settings.polish.copy(model = "still-uploaded"))
        val outcome = SyncEngine(http, host).run(creds, SyncOptions())
        assertTrue((outcome.pushed["setting"] ?: 0) > 0)
        assertEquals(listOf("oversized"), host.state.outbox.map { it.id })
        assertEquals(1, outcome.pending)
        assertEquals(previousSuccess, host.state.lastSync)
        assertEquals(1L, fake.reports.last().pending)
        assertTrue(fake.pushBytes.all { it <= TARGET_PUSH_BYTES })
    }

    @Test
    fun byteBatchesIncludeEscapingAndUtf8() {
        var remaining = (0..30).map { Change(kind = "setting", id = "$it", hlc = "0", payload = "\"é\\".repeat(10_000)) }
        var batches = 0
        while (remaining.isNotEmpty()) {
            val batch = pushBatch(remaining)
            assertTrue(batch.isNotEmpty())
            assertTrue(SyncJson.encodeToString(PushRequest(batch)).toByteArray(Charsets.UTF_8).size <= TARGET_PUSH_BYTES)
            remaining = remaining.drop(batch.size)
            batches++
        }
        assertTrue(batches > 1)
    }

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
