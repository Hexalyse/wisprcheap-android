package io.github.hexalyse.wisprcheap.data

import android.util.Log
import io.github.hexalyse.wisprcheap.core.pipeline.PipelineLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class LogLine(val time: Long, val error: Boolean, val text: String)

/**
 * The log shown in the app (last 3 000 lines) and written to `files/logs/wisprcheap.log`
 * (rotated to `.old` above 1 MB at startup). Same line format as the desktop.
 */
class LogStore(dir: File) : PipelineLog {
    private val file = File(dir, "wisprcheap.log")
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "wc-log") }
    private val buffer = ArrayDeque<LogLine>()
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()
    val logFile: File get() = file

    init {
        dir.mkdirs()
        if (file.length() > 1_000_000) file.renameTo(File(dir, "wisprcheap.log.old"))
        val now = SimpleDateFormat("M/d/yyyy, h:mm:ss a", Locale.US).format(Date())
        writer.execute { runCatching { file.appendText("\n=== WisprCheap started $now (pid ${android.os.Process.myPid()}) ===\n") } }
    }

    override fun info(message: String) = add(message, error = false, prefix = true)

    override fun error(message: String) = add(message, error = true, prefix = true)

    override fun detail(message: String) = add(message, error = false, prefix = false)

    @Synchronized
    private fun add(message: String, error: Boolean, prefix: Boolean) {
        val now = System.currentTimeMillis()
        val text = if (prefix) "[${TIME.format(Date(now))}] $message" else message
        if (error) Log.e(TAG, text) else Log.i(TAG, text)
        for (line in text.lines()) {
            buffer.addLast(LogLine(now, error, line))
            if (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        _lines.value = buffer.toList()
        writer.execute { runCatching { file.appendText(text + "\n") } }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _lines.value = emptyList()
    }

    fun allText(): String = _lines.value.joinToString("\n") { it.text }

    private companion object {
        const val TAG = "WisprCheap"
        const val MAX_LINES = 3000
        val TIME = SimpleDateFormat("h:mm:ss a", Locale.US)
    }
}
