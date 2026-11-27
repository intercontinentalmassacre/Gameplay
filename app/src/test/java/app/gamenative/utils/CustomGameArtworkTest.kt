package app.gamenative.utils

import android.app.Application
import android.net.Uri
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.screen.library.components.GridImageUrls
import app.gamenative.ui.screen.library.components.getGridImageUrl
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CustomGameArtworkTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun sharedDirectoryListingKeepsPreferredCoversAndDistinctSteamGridDbHeader() {
        val generic = folder.newFile("cover.png")
        folder.newFile("coverh.jpg")
        val hero = folder.newFile("CoverH.PNG")
        folder.newFolder("coverv.png")
        folder.newFile("steamgriddb_grid_hero.jpg")
        val header = folder.newFile("steamgriddb_hero.webp")
        val files = folder.root.listFiles()!!

        assertEquals(Uri.fromFile(hero).toString(), CustomGameScanner.findHeroCover(files))
        assertEquals(Uri.fromFile(generic).toString(), CustomGameScanner.findCapsuleCover(files))
        assertEquals(Uri.fromFile(header).toString(), SteamGridDB.findSteamGridDBImage(files, "hero", "grid_"))
    }

    @Test
    fun libraryReadsPrewarmedSteamCoverAndPrefersUserArtwork() {
        val gameId = 1_987_654_321
        val item = LibraryItem(appId = "STEAM_$gameId")
        val cacheDirectory = ReflectionHelpers.callInstanceMethod<File>(CoverCache, "getCacheDir")
        val cached = File(cacheDirectory, "${gameId}__grid_capsule.jpg")
        val previousDataDir = DownloadService.baseDataDirPath
        ReflectionHelpers.setStaticField(DownloadService::class.java, "baseDataDirPath", folder.root.absolutePath)
        try {
            cacheDirectory.mkdirs()
            cached.writeText("cached")
            val cachedUri = "file://${cached.absolutePath}"
            val context = RuntimeEnvironment.getApplication()
            assertEquals(cachedUri, GameImageUtils.getGameImage(item, "capsule"))
            assertEquals(GridImageUrls(cachedUri), getGridImageUrl(context, item, PaneType.GRID_CAPSULE))

            val custom = CustomMediaUtils.getCustomCapsuleFile(gameId).apply { writeText("custom") }
            val customUri = Uri.fromFile(custom).toString()
            assertEquals(customUri, GameImageUtils.getGameImage(item, "capsule"))
            assertEquals(GridImageUrls(customUri, cachedUri), getGridImageUrl(context, item, PaneType.GRID_CAPSULE))
        } finally {
            cached.delete()
            ReflectionHelpers.setStaticField(DownloadService::class.java, "baseDataDirPath", previousDataDir)
        }
    }
}
