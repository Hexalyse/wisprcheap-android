package io.github.hexalyse.wisprcheap.core.pipeline

import io.github.hexalyse.wisprcheap.core.history.Delivered
import io.github.hexalyse.wisprcheap.core.history.HistoryEntry
import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.llm.ChatClient
import io.github.hexalyse.wisprcheap.core.settings.ApiKeys
import io.github.hexalyse.wisprcheap.core.settings.ElevenLabsSettings
import io.github.hexalyse.wisprcheap.core.settings.OpenAiTranscriptionSettings
import io.github.hexalyse.wisprcheap.core.settings.PolishSettings
import io.github.hexalyse.wisprcheap.core.settings.Settings
import io.github.hexalyse.wisprcheap.core.settings.TranscriptionSettings
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.MultipartReader
import okhttp3.OkHttpClient
import okio.Buffer
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** Shared fixtures: a mock server standing in for both the STT and the LLM endpoints, and recording fakes. */
class Harness : AutoCloseable {
    val server = MockWebServer().also { it.start() }
    val url: String = server.url("/").toString().removeSuffix("/")

    /** OkHttp's own silent retry is off so that our "retry once" logic is what gets tested. */
    val http: OkHttpClient = Http.client("wisprcheap-test").newBuilder().retryOnConnectionFailure(false).build()
    val chat = ChatClient(http)
    val keys = ApiKeys(elevenlabs = "el-key", openai = "oa-key")

    val delivered = mutableListOf<DeliveryRequest>()
    val history = mutableListOf<HistoryEntry>()
    val events = mutableListOf<PipelineEvent>()
    val logs = mutableListOf<String>()
    val savedAudio = mutableListOf<ShortArray>()
    var deliveryResult: (DeliveryRequest) -> DeliveryResult = { DeliveryResult(Delivered.INSERTED, "inputConnection") }

    val deps = PipelineDeps(
        delivery = { req -> delivered += req; deliveryResult(req) },
        history = { history += it },
        failedAudio = { pcm, _ -> savedAudio += pcm; "/data/recordings/failed.wav" },
        listener = { events += it },
        log = object : PipelineLog {
            override fun info(message: String) { logs += "I $message" }
            override fun error(message: String) { logs += "E $message" }
            override fun detail(message: String) { logs += "D $message" }
        },
        clock = { Instant.parse("2026-09-29T12:00:00Z") },
    )

    fun settings(transform: (Settings) -> Settings = { it }): Settings = transform(
        Settings(
            transcription = TranscriptionSettings(
                timeoutMs = 2_000,
                elevenlabs = ElevenLabsSettings(baseUrl = url),
                openai = OpenAiTranscriptionSettings(baseUrl = url),
            ),
            polish = PolishSettings(baseUrl = url, timeoutMs = 2_000),
        ),
    )

    fun pipeline(settings: Settings = settings()) = Pipeline.create(settings, keys, chat, http, deps)

    fun json(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    fun stt(text: String) = json("""{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}""")

    fun chatReply(text: String, input: Int = 100, output: Int = 5) = json(
        """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(text)}}}],""" +
            """"usage":{"prompt_tokens":$input,"completion_tokens":$output}}""",
    )

    fun take(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request")

    override fun close() = server.close()

    companion object {
        /** [ms] of a 440 Hz tone at about -12 dBFS. */
        fun speech(ms: Int = 1000) = ShortArray(16 * ms) { (8000 * sin(2 * PI * 440 * it / 16000.0)).toInt().toShort() }

        /** Multipart form fields in order; files are shown as "<file name type size>". */
        fun formFields(req: RecordedRequest): List<Pair<String, String>> {
            val boundary = req.headers["Content-Type"]!!.substringAfter("boundary=")
            val reader = MultipartReader(Buffer().write(req.body!!), boundary)
            val out = mutableListOf<Pair<String, String>>()
            while (true) {
                val part = reader.nextPart() ?: break
                val cd = part.headers["Content-Disposition"]!!
                val name = Regex("name=\"([^\"]+)\"").find(cd)!!.groupValues[1]
                val bytes = part.body.readByteString()
                val file = Regex("filename=\"([^\"]+)\"").find(cd)?.groupValues?.get(1)
                out += name to if (file != null) "<file $file ${part.headers["Content-Type"]} ${bytes.size}>" else bytes.utf8()
            }
            return out
        }
    }
}
