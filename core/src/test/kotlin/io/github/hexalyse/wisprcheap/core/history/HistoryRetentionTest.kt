package io.github.hexalyse.wisprcheap.core.history

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryRetentionTest {
    private fun entry(id: String, ts: String, text: String) = HistoryEntry(
        ts = ts,
        id = id,
        durationSec = 1.5,
        transcription = TranscriptionInfo("elevenlabs", "scribe_v2", 100, 0),
        raw = text,
        text = text,
        words = text.split(" ").size,
        costUsd = Costs(0.001, null, 0.001),
    )

    @Test
    fun sessionOnlyEntriesNeverLeakToDiskSnapshotOrPersistedList() {
        val persisted1 = entry("11111111-1111-4111-8111-111111111111", "2026-10-01T10:00:00.000Z", "persisted one")
        val persisted2 = entry("22222222-2222-4222-8222-222222222222", "2026-10-01T10:05:00.000Z", "persisted two")
        val sessionOnly = entry("33333333-3333-4333-8333-333333333333", "2026-10-01T10:10:00.000Z", "private session dictation")
        val remote = entry("44444444-4444-4444-8444-444444444444", "2026-10-01T10:02:00.000Z", "from laptop")

        // Loaded from disk on startup.
        val buffer = HistoryBuffer(listOf(persisted1, persisted2))

        // User turns "Keep a history" off and dictates: kept in session UI, not written to disk or sync.
        assertFalse(buffer.add(sessionOnly, persist = false))
        assertEquals(listOf(persisted1, persisted2, sessionOnly), buffer.entries)
        assertEquals(listOf(persisted1, persisted2), buffer.persistedEntries)

        // Deleting an older persisted entry writes a snapshot that MUST exclude the session-only entry.
        val delPersisted = buffer.delete(persisted1)
        assertTrue(delPersisted.removed)
        assertTrue(delPersisted.wasPersistent)
        assertEquals(listOf(persisted2), delPersisted.snapshot)
        assertEquals(listOf(persisted2, sessionOnly), buffer.entries)

        // Turning history back on and merging remote entries still keeps the earlier session-only entry off disk.
        val merged = buffer.addAll(listOf(remote), persist = true)
        assertEquals(listOf(remote), merged.added)
        assertEquals(listOf(remote, persisted2), merged.snapshot)
        assertEquals(listOf(remote, persisted2), buffer.persistedEntries)

        // Restarting from the persisted snapshot drops the session-only entry cleanly.
        val restarted = HistoryBuffer(merged.snapshot!!)
        assertEquals(listOf(remote, persisted2), restarted.entries)
        assertTrue(restarted.entries.none { it.id == sessionOnly.id })

        // Deleting a session-only entry does not require rewriting disk or tombstoning on the server.
        val delSession = buffer.delete(sessionOnly)
        assertTrue(delSession.removed)
        assertFalse(delSession.wasPersistent)
        assertNull(delSession.snapshot)
    }

    @Test
    fun ownedAudioPathsConfinesAccessToRecordingsDirectory() {
        val root = Files.createTempDirectory("wisprcheap-audio-test").toFile()
        try {
            val recordings = File(root, "recordings").apply { mkdirs() }
            val owned = File(recordings, "failed-2026-10-01.wav").apply { writeBytes(ByteArray(64)) }
            val outside = File(root, "settings.json").apply { writeText("{}") }
            val traversal = File(recordings, "../settings.json")

            assertNotNull(OwnedAudioPaths.resolve(recordings, owned.absolutePath))
            assertNull(OwnedAudioPaths.resolve(recordings, outside.absolutePath))
            assertNull(OwnedAudioPaths.resolve(recordings, traversal.path))
            assertNull(OwnedAudioPaths.resolve(recordings, recordings.absolutePath))
            assertNull(OwnedAudioPaths.resolve(recordings, "C:/Users/alice/recordings/failed.wav"))
            assertNull(OwnedAudioPaths.resolve(recordings, null))
            assertNull(OwnedAudioPaths.resolve(recordings, "  "))
        } finally {
            root.deleteRecursively()
        }
    }
}
