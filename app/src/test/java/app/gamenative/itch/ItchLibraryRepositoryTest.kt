package app.gamenative.itch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ItchGame
import app.gamenative.db.PluviaDatabase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ItchLibraryRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var database: PluviaDatabase
    private lateinit var dao: app.gamenative.db.dao.ItchGameDao
    private lateinit var auth: ItchAuthManager
    private lateinit var repository: ItchLibraryRepository

    private var accountId = 7L
    private var token: String? = "token-1"
    private var ownedPages = mutableListOf<List<Pair<Long, String>>>()
    private var uploadsBody = ""

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.start()
        http = OkHttpClient()

        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.itchGameDao()

        auth = mockk(relaxed = true)
        every { auth.accountOrNull() } answers {
            token?.let {
                ItchAccount(
                    accessToken = it,
                    scopes = listOf("profile:owned", "game:view:uploads", "profile:me"),
                    userId = accountId,
                    username = "player$accountId",
                    displayName = "Player $accountId",
                )
            }
        }
        every { auth.tokenOrNull() } answers { token }
        every { auth.hasStoredCredentials() } answers { token != null }
        every { auth.isConfigured() } returns true

        repository = ItchLibraryRepository(
            dao = dao,
            auth = auth,
            api = ItchApiClient(
                http = http,
                apiBaseUrl = server.url("/").toString().trimEnd('/'),
                authorizeBaseUrl = server.url("/").toString().trimEnd('/'),
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun gameJson(id: Long, title: String, classification: String = "game") =
        """{"id":$id,"game_id":$id,"created_at":"2024-01-01T00:00:00Z",
            "game":{"id":$id,"classification":"$classification","title":"$title","url":"https://dev.itch.io/$id",
            "cover_url":"https://img.itch.zone/$id.jpg",
            "user":{"username":"dev","display_name":"Dev"}}}"""

    private fun installDispatcher() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return when {
                    path.startsWith("/profile/owned-keys") -> {
                        val page = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
                        val keys = ownedPages.getOrNull(page - 1).orEmpty()
                        val body = if (keys.isEmpty()) {
                            """{"page":$page,"per_page":100,"owned_keys":[]}"""
                        } else {
                            """{"page":$page,"per_page":100,"owned_keys":[${keys.joinToString(",") { gameJson(it.first, it.second) }}]}"""
                        }
                        MockResponse().setResponseCode(200).setBody(body)
                    }
                    path.startsWith("/games/") && path.endsWith("/uploads") ->
                        MockResponse().setResponseCode(200).setBody(uploadsBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @Test
    fun `refresh response from before sign out cannot restore the old library`() = runBlocking {
        coEvery { auth.signOut() } coAnswers {
            token = null
            Result.success(Unit)
        }
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!started.complete(Unit)) return MockResponse().setBody("""{"owned_keys":[]}""")
                if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                return MockResponse().setBody("""{"owned_keys":[${gameJson(45, "Old library")}]}""")
            }
        }
        val refresh = async { repository.refreshLibrary() }
        try {
            withTimeout(5_000) { started.await() }
            assertTrue(repository.signOut().isSuccess)
        } finally {
            release.countDown()
        }
        assertTrue(refresh.await().exceptionOrNull() is ItchNotAuthenticatedException)
        assertTrue(dao.getAllAsList().isEmpty())
        assertNull(ItchGameCache.get(45))
    }

    @Test
    fun `refresh hides previously cached assets and preserves their installation records`() = runBlocking {
        dao.insertAll(listOf(ItchGame(gameId = 2, title = "Textures", isInstalled = true)))
        val game = gameJson(1, "Smiling Terror")
        val asset = gameJson(2, "Textures", "assets")
        server.enqueue(MockResponse().setBody("""{"owned_keys":[$game,$asset]}"""))
        server.enqueue(MockResponse().setBody("""{"owned_keys":{}}"""))
        assertEquals(1, repository.refreshLibrary().getOrThrow().ownedGames)
        assertEquals(listOf("Smiling Terror"), repository.observeGames().first().map { it.title })
        assertTrue(repository.installedGames().isEmpty())
        assertTrue(dao.getByGameId(2)!!.isInstalled)
    }

    @Test
    fun `refresh walks pages and binds the cache to the connected account`() = runBlocking {
        ownedPages = mutableListOf(
            listOf(1L to "One", 2L to "Two"),
            listOf(3L to "Three"),
        )
        installDispatcher()

        val result = repository.refreshLibrary().getOrThrow()

        assertEquals(3, result.ownedGames)
        assertEquals(2, result.pagesFetched)
        assertEquals("Player 7", result.accountLabel)
        val games = dao.getAllAsList()
        // DAO ordering is by title: One, Three, Two.
        assertEquals(listOf(1L, 3L, 2L), games.map { it.gameId })
        assertTrue(games.all { it.isOwned && it.accountId == 7L })
        assertEquals(listOf(1L, 3L, 2L), repository.observeGames().first().map { it.gameId })
        assertEquals(setOf(7L), dao.getKnownAccountIds().toSet())
    }

    @Test
    fun `games removed from the account disappear but installed ones stay`() = runBlocking {
        ownedPages = mutableListOf(listOf(1L to "One", 2L to "Two"))
        installDispatcher()
        repository.refreshLibrary().getOrThrow()
        dao.markInstalled(
            gameId = 2,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/2",
            launchTarget = "Game.exe",
            launchArgs = "",
            uploadId = 20,
            uploadName = "two.zip",
            buildId = 2,
            userVersion = null,
            fileUpdatedAt = null,
            fileSize = 1,
            installedAtMs = 1,
            sizeOnDisk = 1,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 1,
            updateMessage = null,
        )

        // The author unpublished game 1 / the account no longer owns it.
        ownedPages = mutableListOf(listOf(2L to "Two"))
        repository.refreshLibrary().getOrThrow()

        assertNull("removed, non-installed row is gone", dao.getByGameId(1))
        val installed = dao.getByGameId(2)!!
        assertTrue(installed.isInstalled)
        assertTrue(installed.isOwned)
    }

    @Test
    fun `a different itch account does not inherit the previous cache`() = runBlocking {
        ownedPages = mutableListOf(listOf(1L to "One", 2L to "Two"))
        installDispatcher()
        repository.refreshLibrary().getOrThrow()
        dao.markInstalled(
            gameId = 2,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/2",
            launchTarget = "Game.exe",
            launchArgs = "",
            uploadId = 20,
            uploadName = "two.zip",
            buildId = 2,
            userVersion = null,
            fileUpdatedAt = null,
            fileSize = 1,
            installedAtMs = 1,
            sizeOnDisk = 1,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 1,
            updateMessage = null,
        )

        accountId = 8L
        ownedPages = mutableListOf(listOf(3L to "Three"))
        repository.refreshLibrary().getOrThrow()

        assertNull(dao.getByGameId(1))
        val installed = dao.getByGameId(2)!!
        assertNull("previous account binding is cleared", installed.accountId)
        assertFalse(installed.isOwned)
        assertEquals(8L, dao.getByGameId(3)!!.accountId)
        assertEquals(setOf(8L), dao.getKnownAccountIds().toSet())
    }

    @Test
    fun `update check stores evidence for the same upload only`() = runBlocking {
        ownedPages = mutableListOf(listOf(700L to "Pikuniku"))
        uploadsBody = """
            {"uploads":[
              {"id":700,"filename":"pikuniku-win64.zip","size":2048,"type":"default",
               "platforms":{"windows":true},"updated_at":"2026-02-01T00:00:00Z","position":1,
               "build":{"id":501,"user_version":"1.1.0"}},
              {"id":701,"filename":"pikuniku-demo.zip","display_name":"Demo","size":10,
               "type":"default","platforms":{"windows":true},"position":2,
               "build":{"id":900,"user_version":"demo"}}
            ]}
        """.trimIndent()
        installDispatcher()
        repository.refreshLibrary().getOrThrow()
        dao.markInstalled(
            gameId = 700,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/700",
            launchTarget = "Game.exe",
            launchArgs = "",
            uploadId = 700,
            uploadName = "pikuniku-win64.zip",
            buildId = 500,
            userVersion = "1.0.0",
            fileUpdatedAt = "2026-01-01T00:00:00Z",
            fileSize = 2048,
            installedAtMs = 1,
            sizeOnDisk = 4096,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 1,
            updateMessage = null,
        )

        val decision = repository.checkForUpdate(700).getOrThrow()

        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertEquals(700L, decision.upload?.id)
        val stored = dao.getByGameId(700)!!
        assertEquals(ItchGame.UPDATE_STATUS_AVAILABLE, stored.updateStatus)
        assertEquals(ItchUpdateReason.BUILD_CHANGED.name, stored.updateMessage)
        assertEquals(700L, stored.installedUploadId)
    }

    @Test
    fun `missing upload never switches the game to another channel`() = runBlocking {
        ownedPages = mutableListOf(listOf(700L to "Pikuniku"))
        uploadsBody = """
            {"uploads":[
              {"id":701,"filename":"pikuniku-demo.zip","display_name":"Demo","size":10,
               "type":"default","platforms":{"windows":true},"position":1,
               "build":{"id":900,"user_version":"demo"}}
            ]}
        """.trimIndent()
        installDispatcher()
        repository.refreshLibrary().getOrThrow()
        dao.markInstalled(
            gameId = 700,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/700",
            launchTarget = "Game.exe",
            launchArgs = "",
            uploadId = 700,
            uploadName = "pikuniku-win64.zip",
            buildId = 500,
            userVersion = "1.0.0",
            fileUpdatedAt = null,
            fileSize = 2048,
            installedAtMs = 1,
            sizeOnDisk = 4096,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 1,
            updateMessage = null,
        )

        val decision = repository.checkForUpdate(700).getOrThrow()

        assertEquals(ItchUpdateStatus.UPLOAD_MISSING, decision.status)
        assertEquals(700L, dao.getByGameId(700)!!.installedUploadId)
        assertNull(dao.getByGameId(700)!!.selectedUploadId)
    }

    @Test
    fun `library refresh without an account fails without touching the cache`() = runBlocking {
        token = null
        ownedPages = mutableListOf(listOf(1L to "One"))
        installDispatcher()

        val result = repository.refreshLibrary()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ItchNotAuthenticatedException)
        assertTrue(dao.getAllAsList().isEmpty())
    }
}
