package io.github.hexalyse.wisprcheap.data

import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.history.HistoryJson
import io.github.hexalyse.wisprcheap.core.pipeline.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
) : HistoryStore {
    private val _entries = MutableStateFlow(load())

    /** Oldest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()
    private val lock = Mutex()

    override suspend fun add(entry: HistoryEntry) {
        _entries.update { it + entry }
        if (persist()) {
            lock.withLock {
                withContext(Dispatchers.IO) { file.appendText(HistoryJson.encodeLine(entry) + "\n") }
            }
        }
    }

    suspend fun delete(entry: HistoryEntry) {
        _entries.update { it - entry }
        entry.audioFile?.let { runCatching { File(it).delete() } }
        rewrite()
    }

    suspend fun clear() {
        val old = _entries.value
        _entries.value = emptyList()
        old.forEach { e -> e.audioFile?.let { runCatching { File(it).delete() } } }
        rewrite()
    }

    /** Writes the whole history as JSONL (desktop-compatible) to [out]. */
    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        out.bufferedWriter().use { w -> _entries.value.forEach { w.write(HistoryJson.encodeLine(it)); w.write("\n") } }
    }

    private suspend fun rewrite() = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { w -> _entries.value.forEach { w.write(HistoryJson.encodeLine(it)); w.write("\n") } }
            tmp.renameTo(file)
        }
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { HistoryJson.decode(Json.parseToJsonElement(line).jsonObject) }.getOrNull()
        }
    }
}
