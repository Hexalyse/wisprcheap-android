package io.github.hexalyse.wisprcheap.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Server, device token and data key of the sync, encrypted with a Keystore key (like [SecretStore]). */
data class StoredSyncCredentials(val server: String, val token: String, val dataKey: String) {
    override fun toString() = "StoredSyncCredentials($server, ***)"
}

class SyncCredentialStore(private val file: File, private val onError: (String) -> Unit) {
    @Volatile
    var current: StoredSyncCredentials? = load()
        private set

    @Synchronized
    fun save(value: StoredSyncCredentials?) {
        current = value
        if (value == null) {
            file.delete()
            return
        }
        try {
            val json = JSONObject().put("server", value.server).put("token", value.token).put("dataKey", value.dataKey).toString()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(json.toByteArray())
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(cipher.iv.size)
                out.write(cipher.iv)
                out.write(encrypted)
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            onError("Sync credentials could not be saved: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun load(): StoredSyncCredentials? {
        if (!file.exists()) return null
        return try {
            val bytes = AtomicFile(file).readFully()
            val ivLength = bytes[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivLength)))
            val o = JSONObject(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)).decodeToString())
            StoredSyncCredentials(o.getString("server"), o.getString("token"), o.optString("dataKey"))
        } catch (e: Exception) {
            onError("Sync credentials could not be read (${e.javaClass.simpleName}); connect this phone again.")
            null
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wisprcheap_sync"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
