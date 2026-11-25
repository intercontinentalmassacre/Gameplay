package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Cyberpunk2077ExecutableTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun prefersInstalledGameExecutableWhenLauncherExists() {
        folder.newFile("REDprelauncher.exe")
        folder.newFolder("bin", "x64")
        folder.newFile("bin/x64/Cyberpunk2077.exe")

        assertEquals("bin/x64/Cyberpunk2077.exe", installedCyberpunk2077Exe(folder.root.path))
    }

    @Test
    fun fallsBackWhenGameExecutableIsMissingOrIsADirectory() {
        folder.newFile("REDprelauncher.exe")
        assertNull(installedCyberpunk2077Exe(folder.root.path))

        folder.newFolder("bin", "x64", "Cyberpunk2077.exe")
        assertNull(installedCyberpunk2077Exe(folder.root.path))
    }
}
