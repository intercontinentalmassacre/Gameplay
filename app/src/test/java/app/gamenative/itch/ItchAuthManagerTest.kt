package app.gamenative.itch

import android.content.Context
import app.gamenative.Crypto
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItchAuthManagerTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var manager: ItchAuthManager
    private lateinit var filesDir: File
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        val context = org.mockito.Mockito.mock(Context::class.java)
        filesDir = createTempDir("itch-auth")
        org.mockito.Mockito.`when`(context.filesDir).thenReturn(filesDir)

        server = MockWebServer()
        server.start()
        http = OkHttpClient()
        val api = ItchApiClient(
            http = http,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            authorizeBaseUrl = server.url("/").toString().trimEnd('/'),
        )
        manager = ItchAuthManager(context, api)

        // AndroidKeyStore is unavailable in JVM tests; keep the same contract.
        mockkObject(Crypto)
        every { Crypto.encryptToString(any()) } answers {
            Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        every { Crypto.decryptFromString(any()) } answers {
            runCatching { Base64.getDecoder().decode(firstArg<String>()) }.getOrNull()
        }
    }

    @After
    fun tearDown() {
        unmockkObject(Crypto)
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
        filesDir.deleteRecursively()
    }

    private fun dispatcher(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun tokenResponse() = MockResponse().setResponseCode(200).setBody(
        """{"access_token":"token-1","token_type":"bearer","scope":"profile:owned game:view:uploads profile:me"}""",
    )

    private fun profileResponse() = MockResponse().setResponseCode(200).setBody(
        """{"user":{"id":29789,"username":"player","display_name":"Player One","cover_url":"https://img/x.png","url":"https://player.itch.io"}}""",
    )

    private fun redirect(code: String, state: String) =
        "gameplay://itch-oauth/callback?code=$code&state=$state"

    @Test
    fun `beginAuthorization stores a single-use PKCE session and builds the browser URL`() {
        val request = manager.beginAuthorization(clientId = "client-123").getOrThrow()
        assertTrue(request.url.startsWith(server.url("/user/oauth").toString()))
        assertTrue(request.url.contains("client_id=client-123"))
        assertTrue(request.url.contains("response_type=code"))
        assertTrue(request.url.contains("code_challenge_method=S256"))
        assertTrue(request.url.contains("state=${request.state}"))
        assertTrue(ItchPaths.pendingAuthorizationFile(context()).isFile)
        assertNull("credentials are not written before the exchange", manager.accountOrNull())
    }

    @Test
    fun `mismatched state aborts the login and stores nothing`() = runBlocking {
        val request = manager.beginAuthorization(clientId = "client-123").getOrThrow()
        val result = manager.completeAuthorization(redirect("code-1", "wrong-state"))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("state", ignoreCase = true))
        assertFalse(manager.hasStoredCredentials())
        assertFalse(ItchPaths.pendingAuthorizationFile(context()).exists())
        assertEquals(0, requests.size)
    }

    @Test
    fun `foreign redirect is rejected before any exchange`() = runBlocking {
        manager.beginAuthorization(clientId = "client-123").getOrThrow()
        val result = manager.completeAuthorization("https://evil.example/callback?code=x&state=y")
        assertTrue(result.isFailure)
        assertEquals(0, requests.size)
        assertFalse(manager.hasStoredCredentials())
    }

    @Test
    fun `denied authorization is reported and clears the pending session`() = runBlocking {
        val request = manager.beginAuthorization(clientId = "client-123").getOrThrow()
        val result = manager.completeAuthorization(
            "gameplay://itch-oauth/callback?error=access_denied&state=${request.state}",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("access_denied"))
        assertFalse(manager.hasStoredCredentials())
        assertFalse(ItchPaths.pendingAuthorizationFile(context()).exists())
    }

    @Test
    fun `valid redirect exchanges the code and stores the account encrypted`() = runBlocking {
        dispatcher { request ->
            when {
                request.path!!.startsWith("/oauth/token") -> {
                    val body = request.body.readUtf8()
                    assertTrue(body.contains("grant_type=authorization_code"))
                    assertTrue(body.contains("code=code-1"))
                    assertTrue(body.contains("code_verifier="))
                    assertTrue(body.contains("client_id=client-123"))
                    assertTrue(body.contains("redirect_uri=gameplay"))
                    tokenResponse()
                }
                request.path!!.startsWith("/profile") -> {
                    assertEquals("Bearer token-1", request.getHeader("Authorization"))
                    profileResponse()
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val request = manager.beginAuthorization(clientId = "client-123").getOrThrow()
        val result = manager.completeAuthorization(redirect("code-1", request.state))

        val account = result.getOrThrow()
        assertEquals("token-1", account.accessToken)
        assertEquals("Player One", account.label)
        assertEquals("player", account.username)
        assertTrue(account.scopes.contains("profile:me"))
        assertTrue(manager.hasStoredCredentials())
        assertEquals("token-1", manager.tokenOrNull())
        assertFalse(ItchPaths.pendingAuthorizationFile(context()).exists())

        // The raw file must not contain the token in clear text.
        val raw = ItchPaths.credentialsFile(context()).readText()
        assertFalse(raw.contains("token-1"))

        // A code cannot be replayed: the pending session is gone.
        val replay = manager.completeAuthorization(redirect("code-1", request.state))
        assertTrue(replay.isFailure)
    }

    @Test
    fun `signOut revokes the token and deletes local credentials`() = runBlocking {
        dispatcher { request ->
            when {
                request.path!!.startsWith("/oauth/token") -> tokenResponse()
                request.path!!.startsWith("/profile") -> profileResponse()
                request.path!!.startsWith("/oauth/revoke") -> {
                    assertTrue(request.body.readUtf8().contains("token=token-1"))
                    MockResponse().setResponseCode(200).setBody("{}")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val request = manager.beginAuthorization(clientId = "client-123").getOrThrow()
        manager.completeAuthorization(redirect("code-1", request.state)).getOrThrow()
        assertTrue(manager.hasStoredCredentials())

        manager.signOut().getOrThrow()

        assertFalse(manager.hasStoredCredentials())
        assertNull(manager.accountOrNull())
        assertFalse(ItchPaths.credentialsFile(context()).exists())
    }

    @Test
    fun `unconfigured build reports a clear error`() {
        val result = manager.beginAuthorization(clientId = "")
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()!!.message)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("ITCH_CLIENT_ID"))
    }

    private fun context(): Context = org.mockito.Mockito.mock(Context::class.java).also {
        org.mockito.Mockito.`when`(it.filesDir).thenReturn(filesDir)
    }
}
