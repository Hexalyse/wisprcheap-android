package io.github.hexalyse.wisprcheap.core.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An API call failed; the message is shown to the user and stored in the history. */
open class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** No HTTP response arrived (connection problem or timeout): worth one retry for transcription. */
class NetworkException(message: String, cause: Throwable? = null) : ApiException(message, cause)

/** The server answered with a non-2xx status. */
class HttpStatusException(val code: Int, message: String) : ApiException(message)

object Http {
    const val TIMEOUT_MESSAGE = "The operation was aborted due to timeout"

    /**
     * Shared client (keeps connections alive between dictations). Only the per-call timeout applies, so a slow
     * transcription of a long recording isn't cut by a read timeout.
     */
    fun client(userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /**
     * Sends [request] with an overall [timeoutMs] and returns the response body as text.
     * Non-2xx → [HttpStatusException] ("$label: HTTP 401 Unauthorized: <body>"); no response → [NetworkException].
     */
    suspend fun fetch(client: OkHttpClient, request: Request, timeoutMs: Long, label: String): String =
        withContext(Dispatchers.IO) {
            val response = try {
                client.newCall(request).also { it.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }.await()
            } catch (e: IOException) {
                throw networkError(e)
            }
            response.use { r ->
                val body = try {
                    r.body.string()
                } catch (e: IOException) {
                    throw networkError(e)
                }
                if (!r.isSuccessful) throw HttpStatusException(r.code, "$label: ${statusMessage(r.code, r.message, body)}")
                body
            }
        }

    /** "HTTP 401 Unauthorized: <first 500 chars of the body>". */
    fun statusMessage(code: Int, reason: String, body: String): String {
        val phrase = reason.ifBlank { REASONS[code] ?: "" }
        return buildString {
            append("HTTP ").append(code).append(' ').append(phrase)
            if (body.isNotEmpty()) append(": ").append(body.take(500))
        }
    }

    fun networkError(e: IOException): NetworkException =
        if (e is InterruptedIOException) {
            NetworkException(TIMEOUT_MESSAGE, e)
        } else {
            NetworkException(describe(e), e)
        }

    private fun describe(e: Throwable): String {
        val parts = mutableListOf<String>()
        var t: Throwable? = e
        while (t != null && parts.size < 4) {
            val text = t.message ?: t.javaClass.simpleName
            if (parts.none { it.contains(text) }) parts += text
            t = t.cause
        }
        return "error sending request: " + parts.joinToString(": ")
    }

    /** Parses a JSON response body; a malformed body is not retried. */
    fun parseJson(body: String): JsonElement = try {
        Json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw ApiException("error decoding response body: ${e.message}", e)
    }

    fun trimSlash(url: String): String = url.trim().removeSuffix("/")

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    private val REASONS = mapOf(
        400 to "Bad Request", 401 to "Unauthorized", 402 to "Payment Required", 403 to "Forbidden",
        404 to "Not Found", 405 to "Method Not Allowed", 408 to "Request Timeout", 409 to "Conflict",
        413 to "Payload Too Large", 415 to "Unsupported Media Type", 422 to "Unprocessable Entity",
        429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway",
        503 to "Service Unavailable", 504 to "Gateway Timeout",
    )
}
