package app.gamenative.utils

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BestConfigLookupTest {
    @Test
    fun `null best config is a normal no match`() {
        val response = BestConfigService.decodeResponse(
            """{"bestConfig":null,"matchType":"no_match","matchedGpu":null,"matchedDeviceId":null}""",
            "STEAM",
        )
        assertEquals("no_match", response.matchType)
        assertTrue(response.bestConfig.isEmpty())
        assertEquals("STEAM", response.matchedStore)
    }

    @Test
    fun `matching config keeps server values`() {
        val response = BestConfigService.decodeResponse(
            """{"bestConfig":{"containerVariant":"bionic"},"matchType":"exact_gpu_match","matchedGpu":"Adreno","matchedDeviceId":5,"matchedStore":"GOG"}""",
            "STEAM",
        )
        assertEquals("exact_gpu_match", response.matchType)
        assertEquals("Adreno", response.matchedGpu)
        assertEquals(5, response.matchedDeviceId)
        assertEquals("GOG", response.matchedStore)
        assertEquals("""{"containerVariant":"bionic"}""", response.bestConfig.toString())
    }

    @Test
    fun `cancelling optional lookup aborts a stalled response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/api/best-config")).build())
            val reading = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val request = async {
                call.readCancellable { response ->
                    reading.complete(Unit)
                    try {
                        response.body!!.string()
                    } finally {
                        finished.complete(Unit)
                    }
                }
            }

            withTimeout(3_000) { reading.await() }
            request.cancelAndJoin()
            assertTrue(call.isCanceled())
            withTimeout(2_000) { finished.await() }
        }
    }
}
