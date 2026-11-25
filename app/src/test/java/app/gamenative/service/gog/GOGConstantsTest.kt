package app.gamenative.service.gog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GOGConstantsTest {
    private lateinit var gamesDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PrefManager.init(context)
        GOGConstants.init(context)
        gamesDir = context.dataDir.resolve("GOG").resolve("games").resolve("common")
    }

    @Test
    fun testGetGameInstallPath_pathStructure() {
        val path = GOGConstants.getGameInstallPath("Another Game 2026")
        assertEquals(gamesDir.resolve("Another Game 2026").path, path)
    }

    @Test
    fun testSanitizationSpecialChars() {
        val path = GOGConstants.getGameInstallPath("G%ame@With^Special*Chars")
        assertEquals(gamesDir.resolve("GameWithSpecialChars").path, path)
    }
}
