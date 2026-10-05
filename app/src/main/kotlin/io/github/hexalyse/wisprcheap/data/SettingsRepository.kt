package io.github.hexalyse.wisprcheap.data

import android.util.AtomicFile
import io.github.hexalyse.wisprcheap.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Settings as one JSON file, exposed as a StateFlow. Changes apply immediately and are written in the background. */
class SettingsRepository(
    private val file: File,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value
    private val writeLock = Mutex()

    fun update(transform: (Settings) -> Settings) {
        val updated = _settings.updateAndGet(transform)
        scope.launch(Dispatchers.IO) {
            writeLock.withLock { if (_settings.value == updated) save(updated) }
        }
    }

    /** Sync acknowledges a downloaded setting only after its file is durable. */
    suspend fun updateAndSave(transform: (Settings) -> Settings) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            while (true) {
                val before = _settings.value
                val updated = transform(before)
                save(updated, propagate = true)
                if (_settings.compareAndSet(before, updated)) break
            }
        }
    }

    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return try {
            Settings.decode(AtomicFile(file).readFully().decodeToString())
        } catch (e: Exception) {
            onError("Settings could not be read (${e.message}); defaults are used. The old file was kept as settings.json.bad.")
            runCatching { file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true) }
            Settings()
        }
    }

    private fun save(settings: Settings, propagate: Boolean = false) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(settings.encode().toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            onError("Settings could not be saved: ${e.message}")
            if (propagate) throw e
        }
    }
}
