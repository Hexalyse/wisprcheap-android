package io.github.hexalyse.wisprcheap.core.sync

import io.github.hexalyse.wisprcheap.core.http.Http
import io.github.hexalyse.wisprcheap.core.http.HttpStatusException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurityTest {
    @Test
    fun oversizedSyncResponsesAreRejected() = runTest {
        MockWebServer().use { server ->
            server.start()
            val api = SyncApi(OkHttpClient(), server.url("/").toString(), "wcs_test")
            // Check both advertised lengths and chunked bodies with no Content-Length.
            for (response in listOf(
                MockResponse.Builder().body("x".repeat(2 * 1024 * 1024 + 1)).build(),
                MockResponse.Builder().chunkedBody("x".repeat(2 * 1024 * 1024 + 1), 8192).build(),
            )) {
                server.enqueue(response)
                val error = assertFailsWith<SyncApiException> { api.me() }
                assertEquals("response_too_large", error.code)
            }
        }
    }

    @Test
    fun hostileKeyringsFailBeforeAllocatingArgonMemory() {
        for (params in listOf(
            KdfParams(m = Int.MAX_VALUE), KdfParams(m = 65_537), KdfParams(m = -1),
            KdfParams(t = Int.MAX_VALUE), KdfParams(t = 0), KdfParams(p = Int.MAX_VALUE),
            KdfParams(p = 0), KdfParams(m = 7), KdfParams(alg = "argon2i"),
        )) {
            assertFailsWith<SyncCryptoException> { SyncCrypto.derivePassphraseKey("password", ByteArray(16), params) }
        }
        assertFailsWith<SyncCryptoException> { SyncCrypto.derivePassphraseKey("password", ByteArray(0), KdfParams()) }
        KdfParams().validate()
    }

    @Test
    fun redirectsDoNotForwardCredentialsOrPairingCodes() = runTest {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                val client = OkHttpClient() // Even a caller-supplied redirecting client must be restricted.
                fun redirect() = MockResponse.Builder().code(307)
                    .addHeader("Location", target.url("/capture")).build()
                target.enqueue(MockResponse.Builder().body("captured").build())
                origin.enqueue(redirect())
                val request = Request.Builder().url(origin.url("/provider"))
                    .header("xi-api-key", "test-secret").post("private audio or text".toRequestBody()).build()
                assertFailsWith<HttpStatusException> { Http.fetch(client, request, 2_000, "provider") }
                origin.enqueue(redirect())
                assertFailsWith<SyncApiException> {
                    SyncApi.pair(client, origin.url("/").toString(), PairRequest("ABCD2345", "test", "test", "test"))
                }
                origin.enqueue(redirect())
                assertFailsWith<SyncApiException> { SyncApi(client, origin.url("/").toString(), "wcs_test").me() }
                assertEquals(0, target.requestCount, "redirect target must receive no secrets or payloads")
            }
        }
    }
}
