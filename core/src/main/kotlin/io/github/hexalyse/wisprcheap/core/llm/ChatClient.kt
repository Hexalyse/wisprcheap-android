package io.github.hexalyse.wisprcheap.core.llm

import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.settings.LlmOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ChatResult(val text: String, val inputTokens: Long = 0, val outputTokens: Long = 0)

/** One non-streaming call to an OpenAI-compatible Chat Completions endpoint (desktop `llm.rs`). Never retried. */
open class ChatClient(private val http: OkHttpClient) {
    open suspend fun complete(opts: LlmOptions, system: String, user: String, label: String): ChatResult {
        val body = requestBody(opts, system, user)
        val request = Request.Builder()
            .url("${Http.trimSlash(opts.baseUrl)}/chat/completions")
            .apply { if (opts.apiKey.isNotEmpty()) header("Authorization", "Bearer ${opts.apiKey}") }
            .post(Json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()
        return parse(Http.fetch(http, request, opts.timeoutMs, label))
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun requestBody(opts: LlmOptions, system: String, user: String): JsonObject = buildJsonObject {
            put("model", opts.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", user)
                }
            }
            put("stream", false)
            opts.reasoningEffort?.takeIf { it.isNotEmpty() }?.let { put("reasoning_effort", it) }
            opts.temperature?.let { put("temperature", JsonPrimitive(it)) }
        }

        fun parse(body: String): ChatResult {
            val json = Http.parseJson(body).jsonObject
            val content = runCatching {
                json["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull
            }.getOrNull()
            val usage = json["usage"] as? JsonObject
            return ChatResult(
                text = content ?: "",
                inputTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.longOrNull ?: 0,
                outputTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.longOrNull ?: 0,
            )
        }
    }
}
