package io.github.hexalyse.wisprcheap.core.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

data class TranscriptionInfo(val provider: String, val model: String, val ms: Long = 0, val keyterms: Int = 0)

/** LLM step: cleanup, translation, or the command call. */
data class PolishInfo(
    val model: String,
    val ms: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val error: String? = null,
)

data class Costs(val transcription: Double? = null, val polish: Double? = null, val total: Double? = null)

enum class Delivered(val id: String) { INSERTED("inserted"), PASTED("pasted"), CLIPBOARD("clipboard") }

/** One dictation or command, in the desktop `history.jsonl` schema plus the Android extras (`app`, `insertMethod`). */
data class HistoryEntry(
    /** ISO UTC with milliseconds, e.g. "2026-09-29T12:34:56.789Z" (when processing started). */
    val ts: String,
    /** Unique id (UUID). Entries written before sync existed have none. */
    val id: String? = null,
    /** Sync device id of the device that recorded it (null when sync is off). */
    val device: String? = null,
    /** "command" for command mode; null for dictation. */
    val mode: String? = null,
    val durationSec: Double = 0.0,
    val transcription: TranscriptionInfo,
    val polish: PolishInfo? = null,
    val raw: String = "",
    val text: String = "",
    val words: Int = 0,
    val delivered: Delivered? = null,
    val costUsd: Costs = Costs(),
    val retry: Boolean? = null,
    val translation: String? = null,
    val polishSkipped: Int? = null,
    /** Command mode only: the selected text, null when nothing was selected. */
    val selection: String? = null,
    val error: String? = null,
    val audioFile: String? = null,
    val app: String? = null,
    val insertMethod: String? = null,
) {
    val isCommand: Boolean get() = mode == MODE_COMMAND

    /** Counted as a dictation in the stats: no error and some words. */
    val isSuccess: Boolean get() = error == null && words > 0

    companion object {
        const val MODE_COMMAND = "command"
    }
}

object Timestamps {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Like JavaScript's `toISOString()`: always 3 fraction digits. */
    fun iso(instant: Instant): String = ISO.format(instant)

    fun parse(ts: String): Instant? = runCatching { Instant.parse(ts) }.getOrNull()
}

fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0

fun round7(v: Double): Double = (v * 1e7).roundToLong() / 1e7

/** JSON (one line per entry) in the desktop field order; `selection` is written only for commands (null allowed). */
object HistoryJson {
    fun encode(e: HistoryEntry): JsonObject = buildJsonObject {
        put("ts", e.ts)
        e.id?.let { put("id", it) }
        e.device?.let { put("device", it) }
        e.mode?.let { put("mode", it) }
        put("durationSec", e.durationSec)
        putJsonObject("transcription") {
            put("provider", e.transcription.provider)
            put("model", e.transcription.model)
            put("ms", e.transcription.ms)
            put("keyterms", e.transcription.keyterms)
        }
        if (e.polish == null) {
            put("polish", JsonNull)
        } else {
            putJsonObject("polish") {
                put("model", e.polish.model)
                put("ms", e.polish.ms)
                put("inputTokens", e.polish.inputTokens)
                put("outputTokens", e.polish.outputTokens)
                e.polish.error?.let { put("error", it) }
            }
        }
        put("raw", e.raw)
        put("text", e.text)
        put("words", e.words)
        put("delivered", e.delivered?.id?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("costUsd") {
            put("transcription", num(e.costUsd.transcription))
            put("polish", num(e.costUsd.polish))
            put("total", num(e.costUsd.total))
        }
        e.retry?.let { put("retry", it) }
        e.translation?.let { put("translation", it) }
        e.polishSkipped?.let { put("polishSkipped", it) }
        if (e.isCommand) put("selection", e.selection?.let(::JsonPrimitive) ?: JsonNull)
        e.error?.let { put("error", it) }
        e.audioFile?.let { put("audioFile", it) }
        e.app?.let { put("app", it) }
        e.insertMethod?.let { put("insertMethod", it) }
    }

    fun encodeLine(e: HistoryEntry): String = encode(e).toString()

    fun decode(o: JsonObject): HistoryEntry {
        fun str(el: JsonElement?) = (el as? JsonPrimitive)?.contentOrNull
        val t = o["transcription"]?.jsonObject
        val p = o["polish"] as? JsonObject
        val c = o["costUsd"] as? JsonObject
        return HistoryEntry(
            ts = str(o["ts"]) ?: "",
            id = str(o["id"]),
            device = str(o["device"]),
            mode = str(o["mode"]),
            durationSec = (o["durationSec"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            transcription = TranscriptionInfo(
                provider = str(t?.get("provider")) ?: "",
                model = str(t?.get("model")) ?: "",
                ms = (t?.get("ms") as? JsonPrimitive)?.longOrNull ?: 0,
                keyterms = (t?.get("keyterms") as? JsonPrimitive)?.intOrNull ?: 0,
            ),
            polish = p?.let {
                PolishInfo(
                    model = str(it["model"]) ?: "",
                    ms = (it["ms"] as? JsonPrimitive)?.longOrNull ?: 0,
                    inputTokens = (it["inputTokens"] as? JsonPrimitive)?.longOrNull ?: 0,
                    outputTokens = (it["outputTokens"] as? JsonPrimitive)?.longOrNull ?: 0,
                    error = str(it["error"]),
                )
            },
            raw = str(o["raw"]) ?: "",
            text = str(o["text"]) ?: "",
            words = (o["words"] as? JsonPrimitive)?.intOrNull ?: 0,
            delivered = str(o["delivered"])?.let { id -> Delivered.entries.firstOrNull { it.id == id } },
            costUsd = Costs(
                transcription = (c?.get("transcription") as? JsonPrimitive)?.doubleOrNull,
                polish = (c?.get("polish") as? JsonPrimitive)?.doubleOrNull,
                total = (c?.get("total") as? JsonPrimitive)?.doubleOrNull,
            ),
            retry = (o["retry"] as? JsonPrimitive)?.booleanOrNull,
            translation = str(o["translation"]),
            polishSkipped = (o["polishSkipped"] as? JsonPrimitive)?.intOrNull,
            selection = str(o["selection"]),
            error = str(o["error"]),
            audioFile = str(o["audioFile"]),
            app = str(o["app"]),
            insertMethod = str(o["insertMethod"]),
        )
    }

    private fun num(v: Double?): JsonElement = v?.let(::JsonPrimitive) ?: JsonNull
}
