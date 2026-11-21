package io.github.hexalyse.wisprcheap.data

import android.util.AtomicFile
import io.github.hexalyse.wisprcheap.core.history.HistoryBuffer
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.HistoryJson
import io.github.hexalyse.wisprcheap.core.pipeline.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.OutputStream

/**
 * History in the desktop `history.jsonl` format (one JSON object per line), fully loaded in memory.
 * With history turned off, entries are kept for this session only (so the month totals still move)
 * and are never written to disk or uploaded through sync.
 */
class HistoryRepository(
    private val file: File,
    private val persist: () -> Boolean,
    /** Sync device id stamped on new entries (null when sync is off). */
    private val deviceId: () -> String? = { null },
    /** Called with persisted entries the user deleted (sync deletes them on the server too). */
    private val onDeleted: suspend (List<HistoryEntry>) -> Unit = {},
    /** Deletes a local failed-audio file if owned by the recordings store. */
    private val deleteAudio: (String) -> Unit = {},
) : HistoryStore {
    private val buffer = HistoryBuffer(load())
    private val _entries = MutableStateFlow(buffer.entries)
    @Volatile
    private var _persistedEntries: List<HistoryEntry> = buffer.persistedEntries

    /** Oldest first (includes session-only entries for the current run). */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    /** Only entries eligible for disk persistence and sync upload, oldest first. */
    val persistedEntries: List<HistoryEntry>
        get() = _persistedEntries

    private val lock = Mutex()

    override suspend fun add(entry: HistoryEntry) {
        val stamped = if (entry.device == null) entry.copy(device = deviceId()) else entry
        lock.withLock {
            val shouldPersist = persist()
            if (shouldPersist) {
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.outputStreamAppend().use { stream ->
                        stream.write((HistoryJson.encodeLine(stamped) + "\n").toByteArray())
                        stream.fd.sync()
                    }
                }
            }
            buffer.add(stamped, persist = shouldPersist)
            publishLocked()
        }
    }

    /** Entries of other devices (sync download), merged by date. */
    suspend fun addAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        lock.withLock {
            val result = buffer.addAll(entries, persist = persist())
            if (result.added.isEmpty()) return@withLock
            result.snapshot?.let { writeSnapshot(it) }
            publishLocked()
        }
    }

    suspend fun delete(entry: HistoryEntry) {
        val wasPersistent = lock.withLock {
            val result = buffer.delete(entry)
            if (!result.removed) return
            result.snapshot?.let { writeSnapshot(it) }
            publishLocked()
            result.wasPersistent
        }
        entry.audioFile?.let { runCatching { deleteAudio(it) } }
        if (wasPersistent) onDeleted(listOf(entry))
    }

    suspend fun clear() {
        val cleared = lock.withLock {
            val result = buffer.clear()
            if (result.persistedRemoved.isNotEmpty() || file.exists()) {
                writeSnapshot(emptyList())
            }
            publishLocked()
            result
        }
        cleared.allRemoved.forEach { e -> e.audioFile?.let { runCatching { deleteAudio(it) } } }
        if (cleared.persistedRemoved.isNotEmpty()) onDeleted(cleared.persistedRemoved)
    }

    /** Writes the whole history as JSONL (desktop-compatible) to [out]. */
    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        out.bufferedWriter().use { w -> _entries.value.forEach { w.write(HistoryJson.encodeLine(it)); w.write("\n") } }
    }

    private fun publishLocked() {
        _persistedEntries = buffer.persistedEntries
        _entries.value = buffer.entries
    }

    private fun File.outputStreamAppend() = java.io.FileOutputStream(this, true)

    private suspend fun writeSnapshot(entries: List<HistoryEntry>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val writer = stream.bufferedWriter()
            entries.forEach { writer.write(HistoryJson.encodeLine(it)); writer.write("\n") }
            writer.flush()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return AtomicFile(file).readFully().decodeToString().lineSequence().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { HistoryJson.decode(Json.parseToJsonElement(line).jsonObject) }.getOrNull()
        }.toList()
    }
}
