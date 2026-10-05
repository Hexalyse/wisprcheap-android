package io.github.hexalyse.wisprcheap.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** JSON of the sync API: unknown keys ignored, nulls omitted. */
val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// Wire types of the `/v1` API (desktop `sync/SPEC.md` section 7).

@Serializable
data class ErrorBody(val error: String = "", val message: String = "")

@Serializable
data class PairRequest(val code: String, val name: String, val platform: String, val appVersion: String)

@Serializable
data class UserInfo(val id: String, val username: String)

@Serializable
data class DeviceInfo(val id: String, val name: String, val platform: String = "")

@Serializable
data class PairResponse(val token: String, val device: DeviceInfo, val user: UserInfo)

@Serializable
data class Keyring(
    val keyVersion: Long,
    val keyId: String,
    val salt: String,
    val kdf: KdfParams,
    val wrappedKey: String,
)

@Serializable
data class MeResponse(
    val user: UserInfo,
    val device: DeviceInfo,
    val serverTime: String = "",
    val serverVersion: String = "",
    val keyring: Keyring? = null,
    val capabilities: Capabilities = Capabilities(),
)

@Serializable
data class Capabilities(val syncReport: Boolean = false)

@Serializable
data class SyncReportRequest(val uploaded: Long, val downloaded: Long, val pending: Long, val appVersion: String)

@Serializable
data class PutKeyringRequest(val keyId: String, val salt: String, val kdf: KdfParams, val wrappedKey: String)

@Serializable
data class PutKeyringResponse(val keyVersion: Long)

/** One record of the change feed, or one change pushed by a device. */
@Serializable
data class Change(
    val seq: Long? = null,
    val kind: String,
    val id: String,
    val hlc: String,
    val deleted: Boolean = false,
    /** `e1.…` envelope; null for deletions. */
    val payload: String? = null,
    val device: String? = null,
    /** Readable statistics, for `history` records only. */
    val stats: HistoryStats? = null,
) {
    val key: String get() = recordKey(kind, id)
}

@Serializable
data class ChangesResponse(val changes: List<Change>, val nextSince: Long, val hasMore: Boolean)

@Serializable
data class PushRequest(val changes: List<Change>)

const val TARGET_PUSH_BYTES = 768 * 1024

/** Size the actual UTF-8 JSON, including escaping and envelope overhead. */
fun pushBatch(changes: List<Change>): List<Change> {
    var bytes = "{\"changes\":[]}".toByteArray().size
    val batch = mutableListOf<Change>()
    for (change in changes.take(500)) {
        val size = SyncJson.encodeToString(change).toByteArray(Charsets.UTF_8).size + if (batch.isEmpty()) 0 else 1
        if (batch.isNotEmpty() && bytes + size > TARGET_PUSH_BYTES) break
        batch += change
        bytes += size
    }
    return batch
}

@Serializable
enum class PushStatus {
    @SerialName("applied") APPLIED,
    @SerialName("stale") STALE,
    @SerialName("exists") EXISTS,
    @SerialName("rejected") REJECTED,
}

@Serializable
data class PushResult(
    val kind: String,
    val id: String,
    val status: PushStatus,
    val seq: Long? = null,
    val current: Change? = null,
    val error: String? = null,
)

@Serializable
data class PushResponse(val results: List<PushResult>)

@Serializable
data class RenameDeviceRequest(val name: String)

@Serializable
data class DeviceMonth(val device: String, val entries: Long = 0, val words: Long = 0, val totalUsd: Double = 0.0)

@Serializable
data class MonthStats(
    /** `YYYY-MM` in the requested time zone offset. */
    val month: String,
    /** Successful entries (no error, some words), dictations and commands. */
    val entries: Long = 0,
    val commands: Long = 0,
    val failed: Long = 0,
    val words: Long = 0,
    val audioMinutes: Double = 0.0,
    val sttUsd: Double = 0.0,
    val llmUsd: Double = 0.0,
    val totalUsd: Double = 0.0,
    val unknownPrice: Long = 0,
    val byDevice: List<DeviceMonth> = emptyList(),
)

@Serializable
data class StatsResponse(val months: List<MonthStats>, val devices: List<DeviceInfo> = emptyList())

/** What the server may read of a history entry (SPEC.md section 6). */
@Serializable
data class HistoryStats(
    val ts: String,
    val mode: String,
    val durationSec: Double,
    val sttProvider: String,
    val sttModel: String,
    val sttMs: Long,
    val keyterms: Int,
    val llmModel: String? = null,
    val llmMs: Long? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val words: Long,
    val status: String,
    val retry: Boolean = false,
    val costStt: Double? = null,
    val costLlm: Double? = null,
    val costTotal: Double? = null,
) {
    companion object {
        /** From a history entry in the desktop `history.jsonl` schema. */
        fun fromEntry(e: JsonObject): HistoryStats? {
            fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            fun long(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.longOrNull
            fun double(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.doubleOrNull
            val t = e["transcription"] as? JsonObject ?: return null
            val polish = e["polish"] as? JsonObject
            val cost = e["costUsd"] as? JsonObject
            val words = long(e, "words") ?: 0
            val failed = e["error"].let { it != null && it != JsonNull }
            return HistoryStats(
                ts = str(e, "ts") ?: return null,
                mode = str(e, "mode") ?: "dictation",
                durationSec = double(e, "durationSec") ?: 0.0,
                sttProvider = str(t, "provider") ?: "",
                sttModel = str(t, "model") ?: "",
                sttMs = long(t, "ms") ?: 0,
                keyterms = (long(t, "keyterms") ?: 0).toInt(),
                llmModel = str(polish, "model"),
                llmMs = long(polish, "ms"),
                inputTokens = long(polish, "inputTokens") ?: 0,
                outputTokens = long(polish, "outputTokens") ?: 0,
                words = words,
                status = when {
                    failed -> "failed"
                    words == 0L -> "empty"
                    else -> "ok"
                },
                retry = (e["retry"] as? JsonPrimitive)?.booleanOrNull ?: false,
                costStt = double(cost, "transcription"),
                costLlm = double(cost, "polish"),
                costTotal = double(cost, "total"),
            )
        }
    }
}

fun recordKey(kind: String, id: String) = "$kind/$id"
