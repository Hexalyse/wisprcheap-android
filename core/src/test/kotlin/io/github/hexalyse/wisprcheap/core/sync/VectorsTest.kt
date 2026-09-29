package io.github.hexalyse.wisprcheap.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop's shared vectors (`sync/testdata/vectors.json`): the port must reproduce them exactly. */
class VectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/sync/vectors.json")!!.readText(),
    ).jsonObject

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content

    private val dk = DataKey(B64.decode(v["dataKey"]!!.jsonObject.s("bytes")))

    @Test
    fun dataKey() {
        val d = v["dataKey"]!!.jsonObject
        assertEquals(d.s("export"), dk.export())
        assertEquals(d.s("keyId"), dk.keyId)
        assertEquals(dk.keyId, DataKey.import(dk.export()).keyId)
    }

    @Test
    fun passphraseKeys() {
        for (k in v["kdf"]!!.jsonArray.map { it.jsonObject }) {
            val p = k["params"]!!.jsonObject
            val params = KdfParams(p.s("alg"), p["m"]!!.jsonPrimitive.int, p["t"]!!.jsonPrimitive.int, p["p"]!!.jsonPrimitive.int)
            val key = SyncCrypto.derivePassphraseKey(k.s("passphrase"), B64.decode(k.s("salt")), params)
            assertEquals(k.s("key"), B64.encode(key), "params $params")
        }
    }

    @Test
    fun keyWrap() {
        val w = v["wrap"]!!.jsonObject
        val pk = B64.decode(w.s("passphraseKey"))
        assertEquals(w.s("wrappedKey"), SyncCrypto.wrapKey(pk, w.s("userId"), dk, B64.decode(w.s("nonce"))))
        assertEquals(dk.keyId, SyncCrypto.unwrapKey(pk, w.s("userId"), w.s("wrappedKey")).keyId)
        assertFailsWith<WrongPassphraseException> { SyncCrypto.unwrapKey(ByteArray(32), w.s("userId"), w.s("wrappedKey")) }
        assertFailsWith<WrongPassphraseException> { SyncCrypto.unwrapKey(pk, "usr_other", w.s("wrappedKey")) }
    }

    @Test
    fun records() {
        for (r in v["records"]!!.jsonArray.map { it.jsonObject }) {
            val (user, kind, id) = Triple(r.s("userId"), r.s("kind"), r.s("id"))
            val value = r["value"]!!
            assertEquals(r.s("envelope"), dk.encryptRecord(user, kind, id, value, B64.decode(r.s("nonce"))), "$kind/$id")
            assertEquals(canonicalJson(value), canonicalJson(dk.decryptRecord(user, kind, id, r.s("envelope"))))
            assertFailsWith<DecryptException> { dk.decryptRecord(user, kind, "$id-x", r.s("envelope")) }
        }
    }

    @Test
    fun blindIds() {
        for (b in v["blindIds"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(b.s("id"), dk.blindId(b.s("kind"), b.s("key")))
        }
    }

    @Test
    fun hlc() {
        val sorted = v["hlc"]!!.jsonObject["sorted"]!!.jsonArray.map { Hlc.parse(it.jsonPrimitive.content)!! }
        assertTrue(sorted.zipWithNext().all { (a, b) -> a < b && a.toString() < b.toString() })
        sorted.forEach { assertEquals(it, Hlc.parse(it.toString())) }
        for (bad in v["hlc"]!!.jsonObject["invalid"]!!.jsonArray) assertNull(Hlc.parse(bad.jsonPrimitive.content), bad.toString())
        val clock = HlcClock("dev_a")
        val t1 = clock.now(1000)
        val t2 = clock.now(900)
        assertTrue(t2 > t1)
        clock.observe(Hlc(5000, 3, "dev_b"))
        assertEquals(Hlc(5000, 4, "dev_a"), clock.now(1000))
    }

    @Test
    fun legacyIds() {
        // Same as the desktop's `legacy_entry_id` (UUIDv5, URL namespace).
        val id = legacyEntryId("dev_a", "2026-09-29T12:34:56.789Z")
        assertEquals(id, legacyEntryId("dev_a", "2026-09-29T12:34:56.789Z"))
        assertEquals('5', id[14])
        assertEquals(36, id.length)
        // Python: uuid.uuid5(uuid.NAMESPACE_URL, "wisprcheap:dev_a:2026-09-29T12:34:56.789Z")
        assertEquals("e56fc249-9fa1-523b-b544-fc2e2da4f43f", id)
    }
}
