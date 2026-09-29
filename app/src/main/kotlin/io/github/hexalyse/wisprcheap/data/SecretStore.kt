package io.github.hexalyse.wisprcheap.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted with an AES-GCM key that never leaves the Android Keystore.
 * File format: [iv length][iv][ciphertext of a small JSON object]. Never backed up.
 */
class SecretStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _keys = MutableStateFlow(load())
    val keys: StateFlow<ApiKeys> = _keys.asStateFlow()
    val current: ApiKeys get() = _keys.value
    private val writeLock = Mutex()

    fun update(transform: (ApiKeys) -> ApiKeys) {
        val updated = _keys.updateAndGet(transform)
        scope.launch(Dispatchers.IO) {
            writeLock.withLock { if (_keys.value == updated) save(updated) }
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

    private fun save(keys: ApiKeys) {
        try {
            val json = JSONObject()
                .put("elevenlabs", keys.elevenlabs)
                .put("openai", keys.openai)
                .put("polish", keys.polish)
                .put("command", keys.command)
                .put("translation", keys.translation)
                .toString()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(json.toByteArray())
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(iv.size)
                out.write(iv)
                out.write(encrypted)
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            onError("API keys could not be saved: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun load(): ApiKeys {
        if (!file.exists()) return ApiKeys()
        return try {
            val bytes = AtomicFile(file).readFully()
            val ivLength = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            val o = JSONObject(cipher.doFinal(bytes.copyOfRange(1 + ivLength, bytes.size)).decodeToString())
            ApiKeys(
                elevenlabs = o.optString("elevenlabs"),
                openai = o.optString("openai"),
                polish = o.optString("polish"),
                command = o.optString("command"),
                translation = o.optString("translation"),
            )
        } catch (e: Exception) {
            onError("API keys could not be read (${e.javaClass.simpleName}); please enter them again.")
            ApiKeys()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wisprcheap_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
