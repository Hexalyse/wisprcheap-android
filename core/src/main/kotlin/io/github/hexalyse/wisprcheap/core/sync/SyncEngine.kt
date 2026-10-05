package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.HistoryJson
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Last synced version of a record: its HLC and a keyed hash of the value (no plain secrets on disk). */
@Serializable
data class Snap(val hlc: String, val hash: String = "", val deleted: Boolean = false)

/** What a device remembers between syncs (desktop: `state.json`). */
@Serializable
class SyncState(
    var server: String = "",
    var userId: String = "",
    var username: String = "",
    var deviceId: String = "",
    var deviceName: String = "",
    /** Key id of the data key the snapshot was made with (a new key means starting over). */
    var keyId: String = "",
    var cursor: Long = 0,
    /** The first sync (merge) is done. */
    var initialized: Boolean = false,
    var hlc: String = "",
    var snapshot: MutableMap<String, Snap> = mutableMapOf(),
    /** Local changes not pushed yet (encrypted). */
    var outbox: MutableList<Change> = mutableListOf(),
    /** Fetched encrypted records, persisted atomically with the cursor before applying them. */
    var pending: MutableList<Change> = mutableListOf(),
    var historyInbox: MutableList<Change> = mutableListOf(),
    /** Ids of this device's history entries stored on the server. */
    var uploaded: MutableSet<String> = mutableSetOf(),
    /** This device's history entries deleted locally, to delete on the server too. */
    var historyDeletes: MutableSet<String> = mutableSetOf(),
    /** The cursor includes the other devices' history entries. */
    var historyDownload: Boolean = false,
    var lastSync: String? = null,
) {
    fun resetData() {
        cursor = 0
        initialized = false
        snapshot.clear()
        outbox.clear()
        pending.clear()
        historyInbox.clear()
        uploaded.clear()
        historyDeletes.clear()
        historyDownload = false
    }

    fun queue(change: Change) {
        outbox.removeAll { it.kind == change.kind && it.id == change.id }
        outbox += change
    }

    fun encode(): String = SyncJson.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String): SyncState = SyncJson.decodeFromString(serializer(), text)
    }
}

/** Where the engine reads and writes the device's data. */
interface SyncHost {
    val settings: Settings
    val keys: ApiKeys
    val history: List<HistoryEntry>

    /** Replaces the settings and keys (remote changes applied). */
    suspend fun write(settings: Settings, keys: ApiKeys)

    /** Adds the other devices' entries (history download). */
    suspend fun addHistory(entries: List<HistoryEntry>)

    fun loadState(): SyncState
    fun saveState(state: SyncState)
}

data class SyncCredentials(val server: String, val token: String, val dataKey: DataKey, val appVersion: String = "")

data class SyncOptions(val uploadHistory: Boolean = true, val downloadHistory: Boolean = false)

/** The data key is missing or outdated: ask for the passphrase again. */
class NeedsKeyException(message: String) : Exception(message)

data class SyncOutcome(
    val first: Boolean = false,
    val pulled: Int = 0,
    val applied: Map<String, Int> = emptyMap(),
    val pushed: Map<String, Int> = emptyMap(),
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    /** This month, every device. */
    val month: MonthStats? = null,
    val devices: Int = 0,
    val deviceId: String = "",
    val warnings: List<String> = emptyList(),
    val pending: Int = 0,
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        pushed.values.sum().takeIf { it > 0 }?.let { parts += "sent $it change(s)" }
        applied.values.sum().takeIf { it > 0 }?.let { parts += "applied $it change(s) from other devices" }
        if (uploaded > 0) parts += "uploaded $uploaded history entr(ies)"
        if (downloaded > 0) parts += "added $downloaded history entr(ies) from other devices"
        if (pending > 0) parts += "$pending change(s) waiting to sync"
        return if (parts.isEmpty()) "up to date" else parts.joinToString(", ")
    }

    companion object {
        fun describe(counts: Map<String, Int>): String = counts.filterValues { it > 0 }.entries.joinToString(", ") { (kind, n) ->
            val (one, many) = when (kind) {
                SyncProfile.SETTING -> "setting" to "settings"
                SyncProfile.SECRET -> "API key" to "API keys"
                SyncProfile.DICT -> "dictionary term" to "dictionary terms"
                SyncProfile.PAIR -> "translation pair" to "translation pairs"
                SyncProfile.PRICE -> "price" to "prices"
                else -> "record" to "records"
            }
            "$n ${if (n == 1) one else many}"
        }
    }
}

/** Id of a history entry written before sync: UUIDv5(URL namespace, "wisprcheap:<device>:<ts>"). */
fun legacyEntryId(device: String, ts: String): String {
    val ns = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    val bytes = ByteBuffer.allocate(16).putLong(ns.mostSignificantBits).putLong(ns.leastSignificantBits).array()
    val hash = MessageDigest.getInstance("SHA-1").digest(bytes + "wisprcheap:$device:$ts".toByteArray(Charsets.UTF_8))
    hash[6] = ((hash[6].toInt() and 0x0f) or 0x50).toByte()
    hash[8] = ((hash[8].toInt() and 0x3f) or 0x80).toByte()
    val b = ByteBuffer.wrap(hash, 0, 16)
    return UUID(b.long, b.long).toString()
}

/**
 * One sync run (desktop `sync/SPEC.md` section 8): local edits → outbox, pull, merge, push, history,
 * statistics. Runs are serialised.
 */
class SyncEngine(
    private val http: OkHttpClient,
    private val host: SyncHost,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Remembers this device's deleted history entries, deleted on the server at the next sync. */
    suspend fun historyDeleted(entries: List<HistoryEntry>) = mutex.withLock {
        val st = host.loadState()
        if (st.deviceId.isEmpty()) return@withLock
        val ids = entries.filter { it.device == null || it.device == st.deviceId }.map { effectiveId(it, st.deviceId) }
        val toDelete = ids.filter { it in st.uploaded }
        if (toDelete.isNotEmpty()) {
            st.historyDeletes += toDelete
            host.saveState(st)
        }
    }

    suspend fun run(creds: SyncCredentials, options: SyncOptions): SyncOutcome = mutex.withLock {
        val st = host.loadState()
        val server = SyncApi.normalizeServer(creds.server)
        val dk = creds.dataKey
        if (st.keyId != dk.keyId) {
            st.resetData()
            st.keyId = dk.keyId
        }
        val run = Run(SyncApi(http, server, creds.token), dk, st, options, creds.appVersion)
        try {
            run.run()
        } finally {
            st.hlc = run.clock.last().toString()
            host.saveState(st)
        }
    }

    private fun effectiveId(e: HistoryEntry, device: String) = e.id ?: legacyEntryId(device, e.ts)

    private fun hash(dk: DataKey, v: JsonElement) = dk.blindId("hash", canonicalJson(v))

    private inner class Run(val api: SyncApi, val dk: DataKey, val st: SyncState, val options: SyncOptions, val appVersion: String) {
        var clock = HlcClock(st.deviceId.ifEmpty { "dev_unknown" }).also { c -> Hlc.parse(st.hlc)?.let(c::observe) }
        val warnings = mutableListOf<String>()
        val applied = LinkedHashMap<String, Int>()
        val pushed = LinkedHashMap<String, Int>()

        fun warn(message: String) {
            log("[sync] $message")
            warnings += message
        }

        suspend fun run(): SyncOutcome {
            // 0. Local edits since the last sync, before any network call.
            if (st.initialized && st.deviceId.isNotEmpty()) {
                diffLocal()
                host.saveState(st)
            }
            val me = api.me()
            if (st.deviceId != me.device.id || st.userId != me.user.id) {
                val (server, keyId) = st.server to st.keyId
                st.resetData()
                st.server = server
                st.keyId = keyId
                st.snapshot.clear()
                st.deviceId = me.device.id
                st.userId = me.user.id
                clock = HlcClock(me.device.id)
            }
            st.username = me.user.username
            st.deviceName = me.device.name
            val keyring = me.keyring
                ?: throw NeedsKeyException("The encryption was reset on the server: enter a new sync passphrase.")
            if (keyring.keyId != dk.keyId) throw NeedsKeyException("The sync passphrase was reset on another device: enter it again.")

            val download = options.downloadHistory && host.settings.history.enabled
            if (download && !st.historyDownload) st.cursor = 0
            st.historyDownload = download
            val first = !st.initialized

            // 1. Pull.
            val incoming = LinkedHashMap<String, Change>()
            st.pending.forEach { incoming[it.key] = it }
            var pulled = 0
            var downloaded = 0
            while (true) {
                val page = api.pull(st.cursor, PAGE, excludeHistory = !download)
                for (c in page.changes) {
                    Hlc.parse(c.hlc)?.let(clock::observe)
                    if (c.kind == SyncProfile.HISTORY) {
                        if (download && !c.deleted && c.device != st.deviceId) st.historyInbox += c
                        continue
                    }
                    pulled++
                    val snap = st.snapshot[c.key]
                    if (snap != null && hlcGe(snap.hlc, c.hlc)) continue
                    incoming[c.key] = c
                }
                st.cursor = page.nextSince
                st.pending = incoming.values.toMutableList()
                host.saveState(st)
                if (download) {
                    val (added, remaining) = downloadHistory(st.historyInbox)
                    downloaded += added
                    st.historyInbox = remaining.toMutableList()
                    host.saveState(st)
                }
                if (!page.hasMore) break
            }
            // A queued local change and a remote one on the same record: the newer wins.
            val iterator = incoming.entries.iterator()
            while (iterator.hasNext()) {
                val (key, c) = iterator.next()
                val queued = st.outbox.firstOrNull { it.key == key } ?: continue
                if (hlcGe(queued.hlc, c.hlc)) iterator.remove() else st.outbox.remove(queued)
            }

            // 2-4. Decrypt, merge, apply.
            st.pending = incoming.values.toMutableList()
            st.pending = apply(st.pending, first).toMutableList()

            // 5. What the server didn't have (first sync), values normalised by the merge.
            diffLocal()
            host.saveState(st)

            // 6. Push.
            var sawStale = false
            while (st.outbox.isNotEmpty()) {
                val candidates = st.outbox.asSequence().filter { (it.payload?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_PAYLOAD }.take(PAGE).toList()
                if (candidates.isEmpty()) {
                    warn("${st.outbox.size} changes are too large to upload (maximum encrypted payload: 64 KiB); they remain pending")
                    break
                }
                val batch = pushBatch(candidates)
                val resp = api.push(batch)
                for ((sent, r) in batch.zip(resp.results)) {
                    st.outbox.removeAll { it.kind == sent.kind && it.id == sent.id && it.hlc == sent.hlc }
                    when (r.status) {
                        PushStatus.APPLIED, PushStatus.EXISTS -> pushed[sent.kind] = (pushed[sent.kind] ?: 0) + 1
                        PushStatus.STALE -> r.current?.let {
                            Hlc.parse(it.hlc)?.let(clock::observe)
                            st.pending += it
                            sawStale = true
                        }
                        PushStatus.REJECTED -> {
                            if (r.error == "clock_skew") {
                                st.snapshot.remove(sent.key)
                                warn("the server refused a change because this phone's clock is ahead; check the date and time")
                            } else {
                                warn("the server refused ${sent.key}: ${r.error}")
                            }
                        }
                    }
                }
                host.saveState(st)
            }
            if (sawStale) {
                st.pending = apply(st.pending, false).toMutableList()
                host.saveState(st)
            }

            // 7-8. History.
            var uploaded = 0
            if (options.uploadHistory) uploaded = uploadHistory()

            // 9. This month, every device.
            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val month = DateTimeFormatter.ofPattern("yyyy-MM").withZone(zone).format(now)
            val offset = zone.rules.getOffset(now).totalSeconds / 60
            var monthStats: MonthStats? = null
            var devices = 0
            try {
                val stats = api.stats(month, month, offset)
                monthStats = stats.months.firstOrNull { it.month == month }
                devices = stats.devices.size
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is SyncUnauthorizedException) throw e
                warn("statistics unavailable: ${e.message}")
            }

            st.initialized = true
            val pending = st.pending.size + st.historyInbox.size + st.outbox.size
            if (pending == 0) st.lastSync = now.toString()
            host.saveState(st)
            if (me.capabilities.syncReport) {
                try {
                    api.reportSync(SyncReportRequest(
                        (uploaded + pushed.values.sum()).toLong(), (downloaded + applied.values.sum()).toLong(),
                        pending.toLong(), appVersion,
                    ))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    warn("couldn't report the completed sync: ${e.message}")
                }
            }
            return SyncOutcome(first, pulled, applied, pushed, uploaded, downloaded, monthStats, devices, st.deviceId, warnings, pending)
        }

        /** Compares the local profile with the snapshot; every difference is queued with a fresh HLC. */
        fun diffLocal() {
            val local = SyncProfile.profile(host.settings, host.keys, dk)
            for ((key, rec) in local.entries.sortedBy { it.value.order }) {
                if (st.pending.any { it.key == key }) continue
                val h = hash(dk, rec.value)
                val snap = st.snapshot[key]
                if (snap != null && !snap.deleted && snap.hash == h) continue
                val hlc = clock.now().toString()
                st.queue(Change(kind = rec.kind, id = rec.id, hlc = hlc, payload = dk.encryptRecord(st.userId, rec.kind, rec.id, rec.value)))
                st.snapshot[key] = Snap(hlc, h)
            }
            val gone = st.snapshot.filter { (k, s) ->
                !s.deleted && k !in local && st.pending.none { it.key == k } && k.substringBefore('/') in setOf(SyncProfile.DICT, SyncProfile.PAIR, SyncProfile.PRICE)
            }.keys
            for (key in gone) {
                val kind = key.substringBefore('/')
                val id = key.substringAfter('/')
                val hlc = clock.now().toString()
                st.queue(Change(kind = kind, id = id, hlc = hlc, deleted = true))
                st.snapshot[key] = Snap(hlc, deleted = true)
            }
        }

        suspend fun apply(changes: List<Change>, first: Boolean): List<Change> {
            if (changes.isEmpty()) return emptyList()
            val remaining = mutableListOf<Change>()
            val sorted = changes.sortedBy { it.seq ?: Long.MAX_VALUE }
            val local0 = SyncProfile.profile(host.settings, host.keys, dk)
            val decoded = mutableListOf<Pair<Change, JsonElement?>>()
            for (c in sorted) {
                val value = if (c.deleted) {
                    null
                } else {
                    val payload = c.payload ?: continue
                    try {
                        dk.decryptRecord(st.userId, c.kind, c.id, payload)
                    } catch (e: SyncCryptoException) {
                        warn("can't decrypt ${c.key}: ${e.message}")
                        remaining += c
                        continue
                    }
                }
                // First sync of a device that has data: a server deletion or an empty key doesn't erase it.
                if (first) {
                    val local = local0[c.key]
                    if (local != null) {
                        if (value == null) continue
                        val localText = (local.value as? JsonPrimitive)?.content.orEmpty()
                        if (c.kind == SyncProfile.SECRET && (value as? JsonPrimitive)?.content.isNullOrEmpty() && localText.isNotEmpty()) continue
                    }
                }
                decoded += c to value
            }
            if (decoded.isEmpty()) return remaining
            val result = SyncProfile.apply(host.settings, host.keys, dk, decoded.map { (c, v) -> Incoming(c.kind, c.id, v) })
            result.ignored.forEach { warn("ignored $it from another device") }
            if (result.settings != host.settings || result.keys != host.keys) host.write(result.settings, result.keys)
            result.changed.forEach { (kind, n) -> applied[kind] = (applied[kind] ?: 0) + n }
            for ((c, v) in decoded) {
                st.snapshot[c.key] = Snap(c.hlc, v?.let { hash(dk, it) } ?: "", deleted = v == null)
            }
            return remaining
        }

        fun historyChange(e: HistoryEntry): Change? {
            val id = effectiveId(e, st.deviceId)
            val entry = e.copy(id = id, device = st.deviceId)
            val json = HistoryJson.encode(entry)
            val stats = HistoryStats.fromEntry(json) ?: return null
            val payload = dk.encryptRecord(st.userId, SyncProfile.HISTORY, id, json)
            if (payload.length > MAX_PAYLOAD) {
                warn("history entry ${e.ts} is too large to upload")
                return null
            }
            return Change(kind = SyncProfile.HISTORY, id = id, hlc = clock.now().toString(), payload = payload, stats = stats)
        }

        suspend fun uploadHistory(): Int {
            val own = host.history.filter { it.device == null || it.device == st.deviceId }
            val changes = mutableListOf<Change>()
            for (id in st.historyDeletes) {
                changes += Change(kind = SyncProfile.HISTORY, id = id, hlc = clock.now().toString(), deleted = true)
            }
            for (e in own) {
                val id = effectiveId(e, st.deviceId)
                if (id in st.uploaded || id in st.historyDeletes) continue
                historyChange(e)?.let { changes += it }
            }
            var uploaded = 0
            var batch = mutableListOf<Change>()
            var size = 0
            suspend fun flush() {
                if (batch.isEmpty()) return
                val resp = api.push(batch)
                for ((sent, r) in batch.zip(resp.results)) {
                    when (r.status) {
                        PushStatus.APPLIED, PushStatus.EXISTS, PushStatus.STALE -> {
                            if (sent.deleted) {
                                st.historyDeletes.remove(sent.id)
                                st.uploaded.remove(sent.id)
                            } else {
                                st.uploaded += sent.id
                                if (r.status == PushStatus.APPLIED) uploaded++
                            }
                        }
                        PushStatus.REJECTED -> {
                            warn("history entry ${sent.id} refused: ${r.error}")
                            if (sent.deleted) st.historyDeletes.remove(sent.id) else st.uploaded += sent.id
                        }
                    }
                }
                host.saveState(st)
                batch = mutableListOf()
                size = 0
            }
            for (c in changes) {
                val changeBytes = SyncJson.encodeToString(c).toByteArray(Charsets.UTF_8).size + 1
                if (batch.isNotEmpty() && size + changeBytes > TARGET_PUSH_BYTES) flush()
                batch += c
                size += changeBytes
                if (batch.size >= 200 || size > 600_000) flush()
            }
            flush()
            return uploaded
        }

        suspend fun downloadHistory(changes: List<Change>): Pair<Int, List<Change>> {
            if (changes.isEmpty()) return 0 to emptyList()
            val remaining = mutableListOf<Change>()
            val known = host.history.mapNotNull { it.id }.toHashSet()
            val added = mutableListOf<HistoryEntry>()
            for (c in changes) {
                if (c.id in known) continue
                val payload = c.payload ?: continue
                val value = try {
                    dk.decryptRecord(st.userId, SyncProfile.HISTORY, c.id, payload)
                } catch (e: SyncCryptoException) {
                    warn("can't decrypt history entry ${c.id}: ${e.message}")
                    remaining += c
                    continue
                }
                val entry = runCatching { HistoryJson.decode(value.jsonObject) }.getOrNull() ?: continue
                added += entry.copy(id = c.id, device = entry.device ?: c.device)
                known += c.id
            }
            if (added.isNotEmpty()) host.addHistory(added.sortedBy { it.ts })
            return added.size to remaining
        }
    }

    companion object {
        const val PAGE = 500
        const val MAX_PAYLOAD = 64 * 1024

        /** The keyring request for a new passphrase. */
        fun keyringRequest(userId: String, dk: DataKey, passphrase: String, kdf: KdfParams = KdfParams()): PutKeyringRequest {
            val salt = SyncCrypto.randomBytes(16)
            val pk = SyncCrypto.derivePassphraseKey(passphrase, salt, kdf)
            return PutKeyringRequest(dk.keyId, B64.encode(salt), kdf, SyncCrypto.wrapKey(pk, userId, dk))
        }

        /** Unwraps the keyring's data key (throws [WrongPassphraseException]). */
        fun unlock(userId: String, keyring: Keyring, passphrase: String): DataKey {
            val pk = SyncCrypto.derivePassphraseKey(passphrase, B64.decode(keyring.salt), keyring.kdf)
            val dk = SyncCrypto.unwrapKey(pk, userId, keyring.wrappedKey)
            if (dk.keyId != keyring.keyId) throw SyncCryptoException("the server's keyring is inconsistent")
            return dk
        }
    }
}
