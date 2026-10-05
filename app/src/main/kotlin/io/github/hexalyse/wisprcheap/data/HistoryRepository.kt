package io.github.hexalyse.wisprcheap.data

import android.util.AtomicFile

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
 * With history turned off, entries are kept for this session only (so the month totals still move).
 */
class HistoryRepository(
    private val file: File,
    private val persist: () -> Boolean,
    /** Sync device id stamped on new entries (null when sync is off). */
    private val deviceId: () -> String? = { null },
    /** Called with entries the user deleted (sync deletes them on the server too). */
    private val onDeleted: suspend (List<HistoryEntry>) -> Unit = {},
) : HistoryStore {
    private val _entries = MutableStateFlow(load())

    /** Oldest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()
    private val lock = Mutex()

    override suspend fun add(entry: HistoryEntry) {
        val stamped = if (entry.device == null) entry.copy(device = deviceId()) else entry
        lock.withLock {
            if (persist()) {
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.outputStreamAppend().use { stream ->
                        stream.write((HistoryJson.encodeLine(stamped) + "\n").toByteArray())
                        stream.fd.sync()
                    }
                }
            }
            _entries.value = _entries.value + stamped
        }
    }

    /** Entries of other devices (sync download), merged by date. */
    suspend fun addAll(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return
        lock.withLock {
            val known = _entries.value.mapNotNull { it.id }.toHashSet()
            val added = entries.filter { it.id?.let(known::add) ?: true }
            if (added.isEmpty()) return@withLock
            val merged = (_entries.value + added).sortedBy { e -> e.ts }
            if (persist()) writeSnapshot(merged)
            _entries.value = merged
        }
    }

    suspend fun delete(entry: HistoryEntry) {
        lock.withLock {
            val remaining = _entries.value - entry
            writeSnapshot(remaining)
            _entries.value = remaining
        }
        entry.audioFile?.let { runCatching { File(it).delete() } }
        onDeleted(listOf(entry))
    }

    suspend fun clear() {
        val old = lock.withLock {
            val old = _entries.value
            writeSnapshot(emptyList())
            _entries.value = emptyList()
            old
        }
        old.forEach { e -> e.audioFile?.let { runCatching { File(it).delete() } } }
        onDeleted(old)
    }

    /** Writes the whole history as JSONL (desktop-compatible) to [out]. */
    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        out.bufferedWriter().use { w -> _entries.value.forEach { w.write(HistoryJson.encodeLine(it)); w.write("\n") } }
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
