package io.github.hexalyse.wisprcheap.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server couldn't be reached. */
class SyncNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The token was revoked (or the account disabled). */
class SyncUnauthorizedException : Exception("this device was disconnected from the server")

/** Error answer of the server (`{"error": code, "message": …}`). */
class SyncApiException(val status: Int, val code: String, message: String) : Exception(message)

/** Client of the sync API (`/v1`). */
class SyncApi(private val client: OkHttpClient, server: String, private val token: String) {
    val base: String = normalizeServer(server)

    suspend fun me(): MeResponse = call("GET", "/v1/me", null, serializer())

    suspend fun putKeyring(req: PutKeyringRequest, ifMatch: Long? = null): PutKeyringResponse =
        call("PUT", "/v1/keyring", SyncJson.encodeToString(req), serializer(), ifMatch?.toString())

    suspend fun pull(since: Long, limit: Int, excludeHistory: Boolean): ChangesResponse =
        call(
            "GET",
            "/v1/changes?since=$since&limit=$limit" + if (excludeHistory) "&exclude=history" else "",
            null,
            serializer(),
        )

    suspend fun push(changes: List<Change>): PushResponse =
        call("POST", "/v1/changes", SyncJson.encodeToString(PushRequest(changes)), serializer())

    suspend fun stats(from: String, to: String, offsetMinutes: Int): StatsResponse =
        call("GET", "/v1/stats?from=$from&to=$to&offset=$offsetMinutes", null, serializer())

    suspend fun rename(name: String) {
        send(client, request(base, "PATCH", "/v1/device", SyncJson.encodeToString(RenameDeviceRequest(name)), token, null))
    }

    suspend fun unpair() {
        send(client, request(base, "DELETE", "/v1/device", null, token, null))
    }

    private suspend fun <T> call(method: String, path: String, body: String?, s: KSerializer<T>, ifMatch: String? = null): T {
        val text = send(client, request(base, method, path, body, token, ifMatch))
        return decode(text, s)
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()

        /** `https://sync.example.com` from what the user typed (scheme added, trailing `/` removed). */
        fun normalizeServer(url: String): String {
            val t = url.trim().trimEnd('/')
            require(t.isNotEmpty()) { "the server URL is empty" }
            return if (t.startsWith("https://") || t.startsWith("http://")) t else "https://$t"
        }

        /** `POST /v1/pair` (no token yet). */
        suspend fun pair(client: OkHttpClient, server: String, req: PairRequest): PairResponse {
            val text = send(client, request(normalizeServer(server), "POST", "/v1/pair", SyncJson.encodeToString(req), null, null))
            return decode(text, serializer())
        }

        private fun request(base: String, method: String, path: String, body: String?, token: String?, ifMatch: String?): Request =
            Request.Builder()
                .url(base + path)
                .method(method, body?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null))
                .apply {
                    if (token != null) header("Authorization", "Bearer $token")
                    if (ifMatch != null) header("If-Match", ifMatch)
                }
                .build()

        private suspend fun send(client: OkHttpClient, request: Request): String = runInterruptible(Dispatchers.IO) {
            val call = client.newCall(request)
            call.timeout().timeout(60, TimeUnit.SECONDS)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw SyncNetworkException("can't reach the server: ${e.message ?: e.javaClass.simpleName}", e)
            }
            response.use { r ->
                val text = try {
                    r.body.string()
                } catch (e: IOException) {
                    throw SyncNetworkException("can't reach the server: ${e.message ?: e.javaClass.simpleName}", e)
                }
                if (r.code == 401) throw SyncUnauthorizedException()
                if (!r.isSuccessful) {
                    val err = runCatching { SyncJson.decodeFromString<ErrorBody>(text) }.getOrNull()
                    val message = err?.message?.takeIf { it.isNotBlank() } ?: text.take(200).ifBlank { "HTTP ${r.code}" }
                    throw SyncApiException(r.code, err?.error?.ifBlank { null } ?: "error", "$message (HTTP ${r.code})")
                }
                text
            }
        }

        private fun <T> decode(text: String, s: KSerializer<T>): T = try {
            SyncJson.decodeFromString(s, text)
        } catch (e: Exception) {
            throw SyncApiException(0, "bad_response", "unexpected answer from the server; is this a wisprcheap sync server?")
        }
    }
}
