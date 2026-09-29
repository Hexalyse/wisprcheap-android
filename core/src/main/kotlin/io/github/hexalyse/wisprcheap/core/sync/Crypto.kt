package io.github.hexalyse.wisprcheap.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption of the synced data (desktop `sync/SPEC.md` section 3, checked against the
 * shared `vectors.json`):
 * - the data key (DK, 32 random bytes) encrypts everything;
 * - the passphrase key (PK) = Argon2id(NFC(passphrase), salt) wraps DK for the server;
 * - records are sealed with AES-256-GCM under `enc = HKDF(DK)`, ids that would reveal content are
 *   blinded with HMAC-SHA256 under `ids = HKDF(DK)`.
 */
object B64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray = try {
        decoder.decode(text.trim())
    } catch (e: IllegalArgumentException) {
        throw SyncCryptoException("invalid base64: ${e.message}")
    }
}

open class SyncCryptoException(message: String) : Exception(message)

class WrongPassphraseException : SyncCryptoException("wrong sync passphrase")

class DecryptException : SyncCryptoException("could not decrypt the data (wrong key or damaged data)")

@Serializable
data class KdfParams(val alg: String = "argon2id", val m: Int = 65_536, val t: Int = 3, val p: Int = 1)

/** Stable JSON text: object keys sorted (like the desktop's serde_json), no spaces. */
fun canonicalJson(element: JsonElement): String = canonical(element).toString()

private fun canonical(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
    is JsonArray -> JsonArray(e.map(::canonical))
    else -> e
}

object SyncCrypto {
    const val ENVELOPE_PREFIX = "e1."
    const val DATA_KEY_PREFIX = "wck_"
    private const val NONCE_LEN = 12
    private const val PAD_TO = 64
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** PK = Argon2id(NFC(passphrase), salt) → 32 bytes. */
    fun derivePassphraseKey(passphrase: String, salt: ByteArray, params: KdfParams): ByteArray {
        if (params.alg != "argon2id") throw SyncCryptoException("unsupported key derivation ${params.alg}")
        val normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.m)
                .withIterations(params.t)
                .withParallelism(params.p)
                .build(),
        )
        val out = ByteArray(32)
        generator.generateBytes(normalized.toByteArray(Charsets.UTF_8), out)
        return out
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    /** HKDF-SHA256 with no salt, 32 bytes of output (RFC 5869). */
    fun hkdf32(ikm: ByteArray, info: String): ByteArray {
        val prk = hmac(ByteArray(32), ikm)
        return hmac(prk, info.toByteArray(Charsets.UTF_8) + byteArrayOf(1))
    }

    /** `e1.` + base64url(nonce ‖ ciphertext ‖ tag), AES-256-GCM. */
    fun seal(key: ByteArray, aad: ByteArray, plaintext: ByteArray, nonce: ByteArray = randomBytes(NONCE_LEN)): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return ENVELOPE_PREFIX + B64.encode(nonce + cipher.doFinal(plaintext))
    }

    fun open(key: ByteArray, aad: ByteArray, envelope: String): ByteArray {
        val body = envelope.removePrefix(ENVELOPE_PREFIX)
        if (body.length == envelope.length) throw SyncCryptoException("unknown envelope version")
        val bytes = B64.decode(body)
        if (bytes.size < NONCE_LEN + 16) throw SyncCryptoException("envelope too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, NONCE_LEN))
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(bytes, NONCE_LEN, bytes.size - NONCE_LEN)
        } catch (e: AEADBadTagException) {
            throw DecryptException()
        }
    }

    private fun wrapAad(userId: String) = "wisprcheap/keywrap/v1|$userId".toByteArray()

    fun recordAad(userId: String, kind: String, id: String) = "wisprcheap/rec/v1|$userId|$kind|$id".toByteArray()

    /** `{"v":1,"value":…}` padded with spaces to a multiple of 64 bytes (hides exact lengths). */
    fun recordPlaintext(value: JsonElement): ByteArray {
        val text = canonicalJson(buildJsonObject { put("v", 1); put("value", value) }).toByteArray(Charsets.UTF_8)
        val padded = (text.size + PAD_TO - 1) / PAD_TO * PAD_TO
        return text.copyOf(padded).also { it.fill(' '.code.toByte(), text.size, padded) }
    }

    /** Wraps the data key with the passphrase key for the server keyring. */
    fun wrapKey(pk: ByteArray, userId: String, dk: DataKey, nonce: ByteArray = randomBytes(NONCE_LEN)): String =
        seal(pk, wrapAad(userId), dk.bytes(), nonce)

    /** Unwraps the keyring's data key; a wrong passphrase fails here. */
    fun unwrapKey(pk: ByteArray, userId: String, wrapped: String): DataKey {
        val bytes = try {
            open(pk, wrapAad(userId), wrapped)
        } catch (e: DecryptException) {
            throw WrongPassphraseException()
        }
        if (bytes.size != 32) throw SyncCryptoException("wrapped key is not 32 bytes")
        return DataKey(bytes)
    }
}

/** The data key and its derived subkeys. */
class DataKey(dk: ByteArray) {
    private val dk = dk.copyOf()
    private val enc = SyncCrypto.hkdf32(dk, "wisprcheap/enc/v1")
    private val ids = SyncCrypto.hkdf32(dk, "wisprcheap/ids/v1")

    fun bytes(): ByteArray = dk.copyOf()

    /** Public fingerprint (stored in the keyring) so devices notice an encryption reset. */
    val keyId: String = B64.encode(SyncCrypto.hmac(dk, "wisprcheap/keyid/v1".toByteArray()).copyOf(12))

    /** `wck_…`: how a device stores its copy. */
    fun export(): String = SyncCrypto.DATA_KEY_PREFIX + B64.encode(dk)

    /** Blinded record id for ids that would reveal content (dictionary terms, pairs, price models). */
    fun blindId(kind: String, key: String): String =
        B64.encode(SyncCrypto.hmac(ids, "$kind:$key".toByteArray(Charsets.UTF_8))).take(22)

    fun encryptRecord(userId: String, kind: String, id: String, value: JsonElement, nonce: ByteArray? = null): String {
        val plaintext = SyncCrypto.recordPlaintext(value)
        val aad = SyncCrypto.recordAad(userId, kind, id)
        return if (nonce == null) SyncCrypto.seal(enc, aad, plaintext) else SyncCrypto.seal(enc, aad, plaintext, nonce)
    }

    fun decryptRecord(userId: String, kind: String, id: String, envelope: String): JsonElement {
        val plain = SyncCrypto.open(enc, SyncCrypto.recordAad(userId, kind, id), envelope)
        val doc = try {
            SyncJson.parseToJsonElement(plain.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw SyncCryptoException("record JSON: ${e.message}")
        }
        if ((doc["v"] as? JsonPrimitive)?.intOrNull != 1) throw SyncCryptoException("unknown record version")
        return doc["value"] ?: JsonNull
    }

    override fun toString() = "DataKey($keyId)"

    companion object {
        fun generate() = DataKey(SyncCrypto.randomBytes(32))

        fun import(text: String): DataKey {
            val body = text.trim().removePrefix(SyncCrypto.DATA_KEY_PREFIX)
            if (body.length == text.trim().length) throw SyncCryptoException("a data key starts with wck_")
            val bytes = B64.decode(body)
            if (bytes.size != 32) throw SyncCryptoException("a data key is 32 bytes")
            return DataKey(bytes)
        }
    }
}
