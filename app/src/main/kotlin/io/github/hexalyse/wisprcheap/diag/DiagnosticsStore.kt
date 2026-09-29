package io.github.hexalyse.wisprcheap.diag

import android.util.Log
import io.github.hexalyse.wisprcheap.TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One Phase 0 test result. */
data class ProbeResult(
    val ts: Long,
    val test: String,
    val pkg: String?,
    val ok: Boolean,
    val summary: String,
    val details: List<Pair<String, String>> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("test", test)
        put("pkg", pkg ?: JSONObject.NULL)
        put("ok", ok)
        put("summary", summary)
        put("details", JSONArray().apply { details.forEach { (k, v) -> put(JSONArray().put(k).put(v)) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ProbeResult {
            val d = o.optJSONArray("details") ?: JSONArray()
            return ProbeResult(
                ts = o.getLong("ts"),
                test = o.getString("test"),
                pkg = if (o.isNull("pkg")) null else o.getString("pkg"),
                ok = o.getBoolean("ok"),
                summary = o.getString("summary"),
                details = (0 until d.length()).map { i -> d.getJSONArray(i).let { it.getString(0) to it.getString(1) } },
            )
        }
    }
}

/** Results of the Phase 0 tests, kept in memory and appended to a JSONL file (survives restarts). */
class DiagnosticsStore(private val file: File) {
    private val _results = MutableStateFlow(load())
    val results: StateFlow<List<ProbeResult>> = _results.asStateFlow()

    fun add(result: ProbeResult) {
        synchronized(this) {
            _results.update { it + result }
            runCatching { file.appendText(result.toJson().toString() + "\n") }
        }
        Log.i(TAG, "[${result.test}] ${result.pkg} ok=${result.ok} ${result.summary} ${result.details}")
    }

    fun clear() {
        synchronized(this) {
            _results.value = emptyList()
            file.delete()
        }
    }

    private fun load(): List<ProbeResult> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() }.map { ProbeResult.fromJson(JSONObject(it)) }
    }.getOrDefault(emptyList())
}
