package app.gamenative.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ItchGame
import app.gamenative.db.PluviaDatabase
import app.gamenative.itch.ItchInstallerKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class ItchGameDaoTest {
    private lateinit var database: PluviaDatabase
    private lateinit var dao: ItchGameDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.itchGameDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun game(
        id: Long,
        title: String = "Game $id",
        accountId: Long? = 7,
        owned: Boolean = true,
    ) = ItchGame(
        gameId = id,
        title = title,
        url = "https://dev.itch.io/game-$id",
        coverUrl = "https://img.itch.zone/$id.jpg",
        author = "Dev",
        accountId = accountId,
        isOwned = owned,
        addedAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    @Test
    fun `library refresh preserves local install state and the chosen upload`() = runBlocking {
        dao.insertAll(listOf(game(1, title = "Old title")))
        dao.markInstalled(
            gameId = 1,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/1",
            launchTarget = "versions/v1/Game.exe",
            launchArgs = "[\"--save-dir\",\"saves\"]",
            uploadId = 700,
            uploadName = "game-win64.zip",
            buildId = 500,
            userVersion = "1.0.0",
            fileUpdatedAt = "2026-01-01T00:00:00Z",
            fileSize = 2048,
            installedAtMs = 2_000L,
            sizeOnDisk = 4096,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 2_000L,
            updateMessage = null,
        )
        dao.updateSelectedUpload(gameId = 1, uploadId = 700, uploadName = "Pikuniku (Windows)", now = 2_000L)

        dao.upsertPreservingInstallState(
            listOf(game(1, title = "New title").copy(coverUrl = "https://img.itch.zone/new.jpg")),
        )

        val stored = dao.getByGameId(1)!!
        assertEquals("New title", stored.title)
        assertEquals("https://img.itch.zone/new.jpg", stored.coverUrl)
        assertTrue(stored.isInstalled)
        assertEquals(ItchGame.INSTALL_KIND_ZIP, stored.installKind)
        assertEquals("versions/v1/Game.exe", stored.launchTarget)
        assertEquals(700L, stored.selectedUploadId)
        assertEquals(500L, stored.installedBuildId)
        assertEquals(4096L, stored.sizeOnDisk)

        val revision = stored.installedRevisionOrNull()!!
        assertEquals(ItchInstallerKind.ZIP, revision.kind)
        assertEquals(700L, revision.uploadId)
        assertEquals(500L, revision.buildId)
        assertEquals("1.0.0", revision.userVersion)
        assertEquals(2048L, revision.fileSize)
    }

    @Test
    fun `sign out keeps installed games and drops account rows`() = runBlocking {
        dao.insertAll(listOf(game(1), game(2)))
        dao.markInstalled(
            gameId = 1,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/1",
            launchTarget = "Game.exe",
            launchArgs = "",
            uploadId = 10,
            uploadName = "game.zip",
            buildId = null,
            userVersion = null,
            fileUpdatedAt = null,
            fileSize = 1,
            installedAtMs = 1,
            sizeOnDisk = 1,
            updateStatus = ItchGame.UPDATE_STATUS_UNKNOWN,
            checkedAt = 1,
            updateMessage = null,
        )

        // LibraryRepository.signOut() performs exactly these two calls.
        dao.deleteAllNonInstalledGames()
        dao.detachAccountFromInstalledGames()

        assertNull("non-installed account rows are removed", dao.getByGameId(2))
        val installed = dao.getByGameId(1)!!
        assertTrue("installed game stays launchable", installed.isInstalled)
        assertNull("account binding is cleared", installed.accountId)
        assertFalse("ownership is no longer claimed", installed.isOwned)
        assertEquals(listOf(installed), dao.getInstalledGames())
        assertTrue(dao.getOwnedGameIds().isEmpty())
    }

    @Test
    fun `switching accounts removes rows from the previous account`() = runBlocking {
        dao.insertAll(listOf(game(1, accountId = 1), game(2, accountId = 1)))
        dao.markInstalled(
            gameId = 2,
            installKind = ItchGame.INSTALL_KIND_EXE,
            installRoot = "/data/itch/2",
            launchTarget = "Games/Two.exe",
            launchArgs = "",
            uploadId = 20,
            uploadName = "two-setup.exe",
            buildId = null,
            userVersion = null,
            fileUpdatedAt = null,
            fileSize = 10,
            installedAtMs = 1,
            sizeOnDisk = 10,
            updateStatus = ItchGame.UPDATE_STATUS_UNKNOWN,
            checkedAt = 1,
            updateMessage = null,
        )
        assertEquals(listOf(1L), dao.getKnownAccountIds())

        // New account signs in: cached rows bound to account 1 are not reused.
        dao.deleteAllNonInstalledGames()
        dao.detachAccountFromInstalledGames()
        dao.insertAll(listOf(game(3, accountId = 2)))

        assertNull(dao.getByGameId(1))
        assertNull(dao.getByGameId(2)?.accountId)
        assertEquals(2L, dao.getByGameId(3)?.accountId)
        assertEquals(listOf(2L), dao.getKnownAccountIds())
    }

    @Test
    fun `stale ownership is cleared without touching freshly fetched rows`() = runBlocking {
        dao.insertAll(listOf(game(1), game(2)))
        dao.upsertPreservingInstallState(listOf(game(1)))

        val previouslyOwned = dao.getOwnedGameIds().toSet()
        val fetched = setOf(1L)
        val stale = previouslyOwned - fetched
        dao.clearOwnership(stale.toList())
        dao.deleteNonInstalledNotOwned()

        assertEquals(setOf(1L), dao.getOwnedGameIds().toSet())
        assertNull(dao.getByGameId(2))
        assertTrue(dao.getByGameId(1)!!.isOwned)
    }

    @Test
    fun `installed games are exposed through the observing flow`() = runBlocking {
        dao.insertAll(listOf(game(2, title = "Zeta"), game(1, title = "Alpha")))
        val all = dao.observeAll().first()
        assertEquals(listOf("Alpha", "Zeta"), all.map { it.title })
        assertEquals("ITCH_1", all.first().appId)
    }

    @Test
    fun `markUninstalled clears the launch target and update state`() = runBlocking {
        dao.insertAll(listOf(game(1)))
        dao.markInstalled(
            gameId = 1,
            installKind = ItchGame.INSTALL_KIND_ZIP,
            installRoot = "/data/itch/1",
            launchTarget = "Game.exe",
            launchArgs = "[]",
            uploadId = 10,
            uploadName = "game.zip",
            buildId = 5,
            userVersion = "1",
            fileUpdatedAt = "t",
            fileSize = 1,
            installedAtMs = 1,
            sizeOnDisk = 1,
            updateStatus = ItchGame.UPDATE_STATUS_UP_TO_DATE,
            checkedAt = 1,
            updateMessage = null,
        )
        dao.markUninstalled(1)
        val stored = dao.getByGameId(1)!!
        assertFalse(stored.isInstalled)
        assertEquals(ItchGame.INSTALL_KIND_NONE, stored.installKind)
        assertEquals("", stored.launchTarget)
        assertNull(stored.installedUploadId)
        assertNull(stored.installedRevisionOrNull())
    }
}
