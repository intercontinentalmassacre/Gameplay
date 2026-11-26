package app.gamenative.itch

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ItchApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: ItchApiClient
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        client = ItchApiClient(
            http = http,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            authorizeBaseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun dispatcher(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun ownedKey(gameId: Long, title: String) = """
        {
          "id": ${gameId + 1000},
          "game_id": $gameId,
          "downloads": 1,
          "created_at": "2024-01-01T00:00:00Z",
          "game": {
            "id": $gameId,
            "title": "$title",
            "url": "https://dev.itch.io/$title",
            "cover_url": "https://img.itch.zone/$gameId.jpg",
            "traits": ["p_windows"],
            "user": {"username": "dev", "display_name": "Dev"}
          }
        }
    """.trimIndent()

    @Test
    fun `owned keys pagination stops only on an empty page`() = runBlocking {
        dispatcher { request ->
            val page = request.requestUrl!!.queryParameter("page")!!.toInt()
            val body = when (page) {
                1 -> """{"page":1,"per_page":2,"owned_keys":[${ownedKey(1, "one")},${ownedKey(2, "two")}]}"""
                2 -> """{"page":2,"per_page":2,"owned_keys":[${ownedKey(3, "three")}]}"""
                else -> """{"page":3,"per_page":2,"owned_keys":[]}"""
            }
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
        }

        val keys = client.fetchAllOwnedKeys("token-abc", perPage = 2)

        assertEquals(3, keys.size)
        assertEquals(listOf(1L, 2L, 3L), keys.map { it.gameId })
        val pages = requests.map { it.requestUrl!!.queryParameter("page") }
        assertEquals(listOf("1", "2", "3"), pages)
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer token-abc" })
        assertEquals("two", keys[1].game?.title)
    }

    @Test
    fun `empty object on the final page preserves previously fetched keys`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"page":1,"owned_keys":[${ownedKey(7, "seven")}]}"""))
        server.enqueue(MockResponse().setBody("""{"page":2,"owned_keys":{}}"""))
        val keys = client.fetchAllOwnedKeys("token", maxPages = 2)
        assertEquals(listOf(7L), keys.map { it.gameId })
    }

    @Test
    fun `owned keys pagination is bounded`() = runBlocking {
        dispatcher { request ->
            val page = request.requestUrl!!.queryParameter("page")!!.toInt()
            MockResponse().setResponseCode(200).setBody(
                """{"page":$page,"per_page":1,"owned_keys":[${ownedKey(page.toLong(), "g$page")}]}""",
            )
        }
        val keys = client.fetchAllOwnedKeys("token", perPage = 1, maxPages = 4)
        assertEquals(4, keys.size)
        assertEquals(4, requests.size)
    }

    @Test
    fun `unauthorized token is reported as unauthorized`() = runBlocking {
        dispatcher { MockResponse().setResponseCode(401).setBody("""{"errors":["invalid token"]}""") }
        try {
            client.fetchOwnedKeys("bad", page = 1)
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertEquals(ItchApiException.Kind.UNAUTHORIZED, error.kind)
            assertEquals(401, error.statusCode)
            assertEquals("invalid token", error.serverError)
        }
    }

    @Test
    fun `forbidden access is distinguished from an expired token`() = runBlocking {
        dispatcher { MockResponse().setResponseCode(403).setBody("""{"errors":["access denied"]}""") }
        try {
            client.fetchUploads("token", 42)
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertEquals(ItchApiException.Kind.FORBIDDEN, error.kind)
        }
    }

    @Test
    fun `owned download key is sent to itch for both list and download`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"owned_keys":[{"id":12,"game_id":42}]}"""))
        server.enqueue(MockResponse().setBody("""{"uploads":{}}"""))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://cdn.example/game.zip"))

        val key = client.fetchOwnedKeys("token", page = 1, gameId = 42).ownedKeys.single()
        assertTrue(client.fetchUploads("token", 42, key.id).isEmpty())
        assertEquals("https://cdn.example/game.zip", client.requestDownloadUrl("token", 77, key.id))
        assertEquals("42", server.takeRequest().requestUrl!!.queryParameter("game_ids"))
        repeat(2) { assertEquals("12", server.takeRequest().requestUrl!!.queryParameter("download_key_id")) }
    }

    @Test
    fun `uploads are parsed with platform build and type`() = runBlocking {
        dispatcher {
            MockResponse().setResponseCode(200).setBody(
                """
                {"uploads":[
                  {"id":5,"filename":"game-linux.zip","size":10,"type":"default","platforms":{"linux":true},"position":2},
                  {"id":4,"filename":"game-windows.zip","size":2048,"type":"default","channel_name":"windows",
                   "platforms":{"windows":true},"updated_at":"2026-01-02T00:00:00Z","position":1,
                   "build":{"id":77,"version":12,"user_version":"1.4.0"}},
                  {"id":6,"filename":"ost.zip","size":5,"type":"soundtrack","platforms":{"windows":true},"position":3}
                ]}
                """.trimIndent(),
            )
        }
        val uploads = client.fetchUploads("token", 42)
        assertEquals(3, uploads.size)
        val windows = uploads[0]
        assertEquals(4L, windows.id)
        assertEquals("windows", windows.channelName)
        assertEquals(77L, windows.build?.id)
        assertEquals("1.4.0", windows.build?.userVersion)
        assertEquals(true, windows.platforms["windows"])
        assertEquals("soundtrack", uploads[2].type)
    }

    @Test
    fun `download url is requested with the bearer token`() = runBlocking {
        dispatcher { request ->
            assertEquals("/uploads/77/download", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            MockResponse().setResponseCode(200).setBody(
                """{"url":"${server.url("/cdn/game.zip")}"}""",
            )
        }
        val url = client.requestDownloadUrl("token", 77)
        assertEquals(server.url("/cdn/game.zip").toString(), url)
        assertEquals(1, requests.size)
    }

    @Test
    fun `download url redirect is not followed so the token stays on the api host`() = runBlocking {
        dispatcher { request ->
            if (request.path == "/uploads/77/download") {
                MockResponse().setResponseCode(302)
                    .setHeader("Location", server.url("/cdn/redirected.zip").toString())
            } else {
                MockResponse().setResponseCode(200).setBody("should not be fetched")
            }
        }
        val url = client.requestDownloadUrl("token", 77)
        assertEquals(server.url("/cdn/redirected.zip").toString(), url)
        assertEquals(1, requests.size)
    }

    @Test
    fun `authorization code exchange posts the PKCE verifier`() = runBlocking {
        dispatcher { request ->
            assertEquals("/oauth/token", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("grant_type=authorization_code"))
            assertTrue(body.contains("code=the-code"))
            assertTrue(body.contains("code_verifier=the-verifier"))
            assertTrue(body.contains("redirect_uri=gameplay"))
            assertTrue(body.contains("client_id=client-id"))
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"new-token","token_type":"bearer","scope":"profile:owned game:view:uploads"}""",
            )
        }
        val token = client.exchangeAuthorizationCode(
            code = "the-code",
            codeVerifier = "the-verifier",
            clientId = "client-id",
            redirectUri = ItchConfig.REDIRECT_URI,
        )
        assertEquals("new-token", token.accessToken)
        assertEquals("bearer", token.tokenType)
    }

    @Test
    fun `rejected authorization code surfaces the server error`() = runBlocking {
        dispatcher {
            MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
        }
        try {
            client.exchangeAuthorizationCode("code", "verifier", "client-id")
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertNotNull(error.serverError)
            assertTrue(error.serverError!!.contains("invalid_grant"))
        }
    }

    @Test
    fun `profile is fetched for the connected account`() = runBlocking {
        dispatcher {
            MockResponse().setResponseCode(200).setBody(
                """{"user":{"id":9,"username":"player","display_name":"Player One","cover_url":"https://img/x.png"}}""",
            )
        }
        val user = client.fetchProfile("token")
        assertEquals(9L, user.id)
        assertEquals("Player One", user.label)
    }

    @Test
    fun `rate limiting has its own failure kind`() = runBlocking {
        dispatcher { MockResponse().setResponseCode(429).setHeader("Retry-After", "30") }
        try {
            client.fetchOwnedKeys("token", page = 1)
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertEquals(ItchApiException.Kind.RATE_LIMITED, error.kind)
        }
    }

    @Test
    fun `malformed payload is an invalid response`() = runBlocking {
        dispatcher { MockResponse().setResponseCode(200).setBody("<html>not json</html>") }
        try {
            client.fetchOwnedKeys("token", page = 1)
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertEquals(ItchApiException.Kind.INVALID_RESPONSE, error.kind)
        }
    }
}
