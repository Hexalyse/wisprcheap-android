package io.github.hexalyse.wisprcheap.sync

import android.content.Context
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.hexalyse.wisprcheap.BuildConfig
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.sync.DataKey
import io.github.hexalyse.wisprcheap.core.sync.MonthStats
import io.github.hexalyse.wisprcheap.core.sync.NeedsKeyException
import io.github.hexalyse.wisprcheap.core.sync.PairRequest
import io.github.hexalyse.wisprcheap.core.sync.SyncApi
import io.github.hexalyse.wisprcheap.core.sync.SyncApiException
import io.github.hexalyse.wisprcheap.core.sync.SyncCredentials
import io.github.hexalyse.wisprcheap.core.sync.SyncEngine
import io.github.hexalyse.wisprcheap.core.sync.SyncHost
import io.github.hexalyse.wisprcheap.core.sync.SyncNetworkException
import io.github.hexalyse.wisprcheap.core.sync.SyncOptions
import io.github.hexalyse.wisprcheap.core.sync.SyncOutcome
import io.github.hexalyse.wisprcheap.core.sync.SyncProfile
import io.github.hexalyse.wisprcheap.core.sync.SyncState
import io.github.hexalyse.wisprcheap.core.sync.SyncUnauthorizedException
import io.github.hexalyse.wisprcheap.core.sync.MeResponse
import io.github.hexalyse.wisprcheap.data.HistoryRepository
import io.github.hexalyse.wisprcheap.data.LogStore
import io.github.hexalyse.wisprcheap.data.SecretStore
import io.github.hexalyse.wisprcheap.data.SettingsRepository
import io.github.hexalyse.wisprcheap.data.StoredSyncCredentials
import io.github.hexalyse.wisprcheap.data.SyncCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The optional sync of this phone: pairing, the passphrase, and background runs (at startup, a few
 * seconds after a settings change or a new history entry, every 15 minutes through WorkManager, and on
 * "Sync now"), with backoff when the server can't be reached.
 */
class SyncManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val history: () -> HistoryRepository,
    private val http: OkHttpClient,
    private val log: LogStore,
    private val scope: CoroutineScope,
) {
    enum class Phase { OFF, SYNCING, OK, OFFLINE, DISCONNECTED, NEEDS_KEY, ERROR }

    data class Status(
        val phase: Phase = Phase.OFF,
        val server: String = "",
        val username: String = "",
        val deviceName: String = "",
        val lastSync: Long? = null,
        val message: String = "",
        /** This month, every device. */
        val month: MonthStats? = null,
        val devices: Int = 0,
    )

    /** Pairing done up to the passphrase. */
    data class PendingPairing(val server: String, val token: String, val me: MeResponse) {
        val newPassphrase: Boolean get() = me.keyring == null
    }

    private val dir = File(context.filesDir, "sync")
    private val stateFile = File(dir, "state.json")
    private val credentials = SyncCredentialStore(File(context.filesDir, "sync-credentials.bin")) { log.error(it) }

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Server and code from a `wisprcheap://pair` link, shown on the Sync page. */
    val pendingLink = MutableStateFlow<Pair<String, String>?>(null)

    @Volatile
    var deviceId: String? = if (credentials.current != null) loadState().deviceId.ifEmpty { null } else null
        private set

    val connected: Boolean get() = credentials.current != null

    private val host = object : SyncHost {
        override val settings: Settings get() = this@SyncManager.settings.current
        override val keys: ApiKeys get() = secrets.current
        override val history: List<HistoryEntry> get() = this@SyncManager.history().entries.value

        override suspend fun write(settings: Settings, keys: ApiKeys) {
            this@SyncManager.settings.update { SyncProfile.mergeSynced(it, settings) }
            if (keys != secrets.current) secrets.update { keys }
        }

        override suspend fun addHistory(entries: List<HistoryEntry>) = this@SyncManager.history().addAll(entries)

        override fun loadState(): SyncState = this@SyncManager.loadState()

        override fun saveState(state: SyncState) = this@SyncManager.saveState(state)
    }

    private val engine = SyncEngine(http, host) { log.info(it) }
    private var failures = 0
    private var retryJob: Job? = null

    private fun initialStatus(): Status {
        val c = credentials.current ?: return Status()
        val st = loadState()
        return Status(
            phase = Phase.OK,
            server = c.server,
            username = st.username,
            deviceName = st.deviceName,
            lastSync = st.lastSync?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    private fun loadState(): SyncState = try {
        if (stateFile.exists()) SyncState.decode(AtomicFile(stateFile).readFully().decodeToString()) else SyncState()
    } catch (e: Exception) {
        log.error("[sync] The sync state was damaged; starting over.")
        SyncState()
    }

    @Synchronized
    private fun saveState(state: SyncState) {
        dir.mkdirs()
        val atomic = AtomicFile(stateFile)
        val out = atomic.startWrite()
        try {
            out.write(state.encode().toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            log.error("[sync] Can't save the sync state: ${e.message}")
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { settings.settings.drop(1).debounce(3_000).collect { if (connected) run() } }
        scope.launch { secrets.keys.drop(1).debounce(3_000).collect { if (connected) run() } }
        scope.launch {
            history().entries.map { it.size }.distinctUntilChanged().drop(1).debounce(10_000).collect {
                if (connected && settings.current.sync.uploadHistory) run()
            }
        }
        if (connected) {
            schedulePeriodic()
            scope.launch {
                delay(2_000)
                run()
            }
        }
    }

    fun syncNow() {
        scope.launch { run() }
    }

    /** Deletes this phone's entries on the server too (next sync). */
    suspend fun historyDeleted(entries: List<HistoryEntry>) {
        if (connected) engine.historyDeleted(entries)
    }

    /** One sync; never throws. */
    suspend fun run(): Result<SyncOutcome> {
        val c = credentials.current ?: return Result.failure(IllegalStateException("sync is off"))
        val dk = runCatching { DataKey.import(c.dataKey) }.getOrNull()
        if (dk == null) {
            _status.update { it.copy(phase = Phase.NEEDS_KEY, message = "Enter the sync passphrase.") }
            return Result.failure(NeedsKeyException("missing key"))
        }
        retryJob?.cancel()
        _status.update { it.copy(phase = Phase.SYNCING) }
        val started = System.nanoTime()
        val s = settings.current.sync
        return try {
            val out = engine.run(SyncCredentials(c.server, c.token, dk), SyncOptions(s.uploadHistory, s.downloadHistory))
            failures = 0
            deviceId = out.deviceId
            val st = loadState()
            _status.update {
                it.copy(
                    phase = Phase.OK, server = c.server, username = st.username, deviceName = st.deviceName,
                    lastSync = System.currentTimeMillis(), message = "", month = out.month, devices = out.devices,
                )
            }
            val secs = "%.1f".format(java.util.Locale.ROOT, (System.nanoTime() - started) / 1e9)
            if (out.first) {
                val applied = SyncOutcome.describe(out.applied).ifEmpty { null }?.let { "$it updated from the server" }
                val pushed = SyncOutcome.describe(out.pushed).ifEmpty { null }?.let { "$it uploaded" }
                log.info("[sync] First sync done: ${listOfNotNull(applied, pushed).joinToString(", ").ifEmpty { "nothing to merge" }} ($secs s).")
            } else if (out.summary() != "up to date") {
                log.info("[sync] ${out.summary()} ($secs s).")
            }
            Result.success(out)
        } catch (e: Exception) {
            val (phase, retry) = when (e) {
                is SyncNetworkException -> Phase.OFFLINE to true
                is SyncApiException -> Phase.ERROR to true
                is SyncUnauthorizedException -> Phase.DISCONNECTED to false
                is NeedsKeyException -> Phase.NEEDS_KEY to false
                else -> Phase.ERROR to false
            }
            val message = e.message ?: e.javaClass.simpleName
            if (retry) {
                failures++
                val wait = minOf(30_000L shl minOf(failures - 1, 10), 30 * 60_000L)
                val waitText = if (wait < 90_000) "${wait / 1000} s" else "${wait / 60_000} min"
                log.error("[sync] $message; retrying in $waitText")
                retryJob = scope.launch {
                    delay(wait)
                    run()
                }
            } else {
                log.error("[sync] $message")
            }
            _status.update { it.copy(phase = phase, message = message) }
            Result.failure(e)
        }
    }

    // --- Pairing and passphrase ---

    /** Step 1: redeem the code. Then [finishPairing] with the passphrase. */
    suspend fun beginPairing(server: String, code: String, name: String): PendingPairing {
        val normalized = SyncApi.normalizeServer(server)
        val paired = SyncApi.pair(
            http, normalized,
            PairRequest(normalizeCode(code), name.trim().ifEmpty { android.os.Build.MODEL }, "android", BuildConfig.VERSION_NAME),
        )
        val me = SyncApi(http, normalized, paired.token).me()
        return PendingPairing(normalized, paired.token, me)
    }

    /** Step 2: create or unlock the encryption, save everything and run the first sync. */
    suspend fun finishPairing(p: PendingPairing, passphrase: String): Result<SyncOutcome> {
        val api = SyncApi(http, p.server, p.token)
        val dk = setupKey(api, p.me, passphrase)
        saveState(
            SyncState(
                server = p.server, userId = p.me.user.id, username = p.me.user.username,
                deviceId = p.me.device.id, deviceName = p.me.device.name, keyId = dk.keyId,
            ),
        )
        credentials.save(StoredSyncCredentials(p.server, p.token, dk.export()))
        deviceId = p.me.device.id
        _status.value = Status(Phase.SYNCING, p.server, p.me.user.username, p.me.device.name)
        log.info("[sync] Connected to ${p.server} as \"${p.me.device.name}\".")
        schedulePeriodic()
        return run()
    }

    /** Abandons a pairing stopped at the passphrase (revokes the new token). */
    suspend fun cancelPairing(p: PendingPairing) {
        runCatching { SyncApi(http, p.server, p.token).unpair() }
    }

    /** Creates the keyring (first device, or after a reset) or unwraps the existing one. */
    private suspend fun setupKey(api: SyncApi, me: MeResponse, passphrase: String): DataKey = withContext(Dispatchers.Default) {
        val keyring = me.keyring
        if (keyring != null) return@withContext SyncEngine.unlock(me.user.id, keyring, passphrase)
        val dk = DataKey.generate()
        try {
            api.putKeyring(SyncEngine.keyringRequest(me.user.id, dk, passphrase))
            dk
        } catch (e: SyncApiException) {
            if (e.code != "keyring_exists") throw e
            SyncEngine.unlock(me.user.id, api.me().keyring ?: throw e, passphrase)
        }
    }

    /** Whether the server needs a new passphrase (encryption reset) rather than the existing one. */
    suspend fun needsNewPassphrase(): Boolean {
        val c = credentials.current ?: return false
        return SyncApi(http, c.server, c.token).me().keyring == null
    }

    /** Enters the passphrase again after it was reset on another device (or sets a new one). */
    suspend fun unlock(passphrase: String): Result<SyncOutcome> {
        val c = credentials.current ?: return Result.failure(IllegalStateException("sync is off"))
        val api = SyncApi(http, c.server, c.token)
        val dk = setupKey(api, api.me(), passphrase)
        credentials.save(c.copy(dataKey = dk.export()))
        return run()
    }

    /** Changes the passphrase (same data key: the other devices keep working). */
    suspend fun changePassphrase(passphrase: String) {
        val c = credentials.current ?: throw IllegalStateException("sync is off")
        val dk = DataKey.import(c.dataKey)
        val api = SyncApi(http, c.server, c.token)
        val me = api.me()
        val keyring = me.keyring ?: throw NeedsKeyException("The encryption was reset on the server.")
        if (keyring.keyId != dk.keyId) throw NeedsKeyException("The passphrase was reset on another device.")
        val req = withContext(Dispatchers.Default) { SyncEngine.keyringRequest(me.user.id, dk, passphrase) }
        api.putKeyring(req, keyring.keyVersion)
        log.info("[sync] Sync passphrase changed.")
    }

    suspend fun rename(name: String) {
        val c = credentials.current ?: return
        SyncApi(http, c.server, c.token).rename(name.trim())
        val st = loadState()
        st.deviceName = name.trim()
        saveState(st)
        _status.update { it.copy(deviceName = name.trim()) }
    }

    /** Revokes this phone's token; settings, dictionary and history stay. */
    suspend fun disconnect() {
        val c = credentials.current ?: return
        try {
            SyncApi(http, c.server, c.token).unpair()
        } catch (e: SyncUnauthorizedException) {
            // already revoked
        } catch (e: Exception) {
            log.error("[sync] Couldn't tell the server (${e.message}); remove the device from its web page.")
        }
        credentials.save(null)
        stateFile.delete()
        deviceId = null
        retryJob?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        _status.value = Status()
        log.info("[sync] Disconnected from ${c.server}.")
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "wisprcheap-sync"

        /** Codes are shown as `ABCD-EFGH`: accept any case, spaces and dashes. */
        fun normalizeCode(code: String) = code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

        /** `wisprcheap://pair?server=…&code=…` → (server, code). */
        fun parseLink(uri: android.net.Uri?): Pair<String, String>? {
            if (uri == null || uri.scheme != "wisprcheap" || uri.host != "pair") return null
            val server = uri.getQueryParameter("server") ?: return null
            val code = uri.getQueryParameter("code") ?: return null
            return server to code
        }
    }
}

/** Periodic sync (15 min, when online). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = WisprApp.graph.sync
        if (!sync.connected) return Result.success()
        val result = sync.run()
        return if (result.isSuccess || result.exceptionOrNull() !is SyncNetworkException) Result.success() else Result.retry()
    }
}
