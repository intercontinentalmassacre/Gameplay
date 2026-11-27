package app.gamenative.ui.util

import com.winlator.container.ContainerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkingContainerConfigTest {
    @Test
    fun latestBookmarkIsPerGameAndPreservesCurrentIdentityAndLaunchSettings() {
        val context = RuntimeEnvironment.getApplication()
        val appId = "STEAM_42"
        val config = ContainerData(
            name = "Old name",
            executablePath = "old.exe",
            installPath = "/old/game",
            drives = "D:/old/game",
            execArgs = "C:/old/save",
            envVars = "SAVE_PATH=C:/old/save",
            graphicsDriver = "virgl",
            screenSize = "800x600",
        )
        val slot = WorkingContainerConfig(context, appId)
        assertTrue(slot.save(config))
        assertTrue(slot.save(config.copy(screenSize = "1280x720")))
        assertNull(WorkingContainerConfig(context, "CUSTOM_GAME_42").load())

        val bookmark = requireNotNull(WorkingContainerConfig(context, appId).load())
        assertNotEquals(config.executablePath, bookmark.executablePath)
        val current = config.copy(
            name = "Renamed game",
            executablePath = "new.exe",
            installPath = "/new/game",
            drives = "D:/new/game",
            execArgs = "C:/new/save",
            envVars = "SAVE_PATH=C:/new/save",
            graphicsDriver = "turnip",
            wineVersion = "new-wine",
            localSavesOnly = true,
            useSteamInput = true,
        )
        assertEquals(
            current.copy(screenSize = "1280x720", graphicsDriver = "virgl"),
            current.withWorkingSettings(bookmark),
        )
    }
}
