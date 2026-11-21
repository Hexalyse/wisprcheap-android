package io.github.hexalyse.wisprcheap.core.history

import java.io.File

/**
 * In-memory history buffer that tracks per-entry persistence eligibility.
 *
 * Entries recorded while "Keep a history" is off remain visible for the current session
 * (so monthly totals still move) without ever leaking into disk snapshots or sync uploads.
 */
class HistoryBuffer(initialPersisted: List<HistoryEntry> = emptyList()) {
    private data class Item(val entry: HistoryEntry, val persistent: Boolean)

    private val items = initialPersisted.map { Item(it, persistent = true) }.toMutableList()

    /** All entries (persistent + session-only), oldest first. */
    val entries: List<HistoryEntry>
        get() = items.map { it.entry }

    /** Only entries eligible for disk persistence and sync upload, oldest first. */
    val persistedEntries: List<HistoryEntry>
        get() = items.filter { it.persistent }.map { it.entry }

    /** Appends [entry]; returns true if it should be appended to disk. */
    fun add(entry: HistoryEntry, persist: Boolean): Boolean {
        items += Item(entry, persistent = persist)
        return persist
    }

    data class AddAllResult(
        val added: List<HistoryEntry>,
        /** Updated persistent snapshot to write to disk, or null if no disk write is needed. */
        val snapshot: List<HistoryEntry>?,
    )

    /** Merges remote entries sorted by timestamp, deduplicating by entry id. */
    fun addAll(incoming: List<HistoryEntry>, persist: Boolean): AddAllResult {
        if (incoming.isEmpty()) return AddAllResult(emptyList(), null)
        val known = items.mapNotNull { it.entry.id }.toHashSet()
        val added = incoming.filter { it.id?.let(known::add) ?: true }
        if (added.isEmpty()) return AddAllResult(emptyList(), null)
        added.forEach { items += Item(it, persistent = persist) }
        items.sortBy { it.entry.ts }
        return AddAllResult(added, if (persist) persistedEntries else null)
    }

    data class DeleteResult(
        val removed: Boolean,
        val wasPersistent: Boolean,
        /** Updated persistent snapshot to write to disk, or null if no disk write is needed. */
        val snapshot: List<HistoryEntry>?,
    )

    /** Removes the first item matching [entry]. Never includes session-only items in the returned snapshot. */
    fun delete(entry: HistoryEntry): DeleteResult {
        val index = items.indexOfFirst { it.entry == entry }
        if (index < 0) return DeleteResult(removed = false, wasPersistent = false, snapshot = null)
        val removed = items.removeAt(index)
        return DeleteResult(
            removed = true,
            wasPersistent = removed.persistent,
            snapshot = if (removed.persistent) persistedEntries else null,
        )
    }

    data class ClearResult(
        val allRemoved: List<HistoryEntry>,
        val persistedRemoved: List<HistoryEntry>,
    )

    fun clear(): ClearResult {
        val all = items.map { it.entry }
        val persisted = items.filter { it.persistent }.map { it.entry }
        items.clear()
        return ClearResult(all, persisted)
    }
}

/** Confines failed-recording reads and deletions to files strictly owned by the local recordings store. */
object OwnedAudioPaths {
    /** Returns the canonical file if [path] is an existing regular file inside [rootDir], else null. */
    fun resolve(rootDir: File, path: String?): File? {
        val trimmed = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val root = rootDir.canonicalFile.toPath()
            val candidate = File(trimmed).canonicalFile
            val candidatePath = candidate.toPath()
            if (candidatePath != root && candidatePath.startsWith(root) && candidate.isFile) candidate else null
        }.getOrNull()
    }
}
