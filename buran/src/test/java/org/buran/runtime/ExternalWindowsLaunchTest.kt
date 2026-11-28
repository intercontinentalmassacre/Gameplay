package org.buran.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalWindowsLaunchTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun steamAppIdAndWorkingDirectorySurviveResolution() {
        val root = temp.newFolder("steam-install")
        root.resolve("bin").mkdir()
        root.resolve("bin/Haiku.exe").createNewFile()

        val request = ExternalWindowsLaunch.resolve(
            "steam:123", root, "bin/Haiku.exe", 64, workingDirectory = ".", steamAppId = 123,
        )

        assertEquals(123, request.steamAppId)
        assertEquals("", request.relativeWorkingDirectory)
        assertEquals("bin/Haiku.exe", request.game.exe)
        val local = ExternalWindowsLaunch.resolve("steam:123", root, "bin/Haiku.exe", 64)
        assertNull(local.steamAppId)
        assertEquals("bin", local.relativeWorkingDirectory)
        assertEquals(local.game.id, request.game.id)
    }

    @Test
    fun rejectsInvalidSteamAppIdAndWorkingDirectoryOutsideInstallation() {
        val root = temp.newFolder("steam-install")
        root.resolve("game.exe").createNewFile()

        assertThrows(IllegalArgumentException::class.java) {
            ExternalWindowsLaunch.resolve("steam:123", root, "game.exe", 64, steamAppId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalWindowsLaunch.resolve("steam:123", root, "game.exe", 64, workingDirectory = "..", steamAppId = 123)
        }
    }

    @Test
    fun remoteDirectoryMustBelongToTheLaunchedApp() {
        val root = temp.newFolder("steam-install")
        root.resolve("game.exe").createNewFile()
        val remote = temp.newFolder("steam-userdata", "42", "123", "remote")

        val request = ExternalWindowsLaunch.resolve(
            "steam:123", root, "game.exe", 64, steamAppId = 123, steamRemoteDirectory = remote,
        )

        assertEquals(remote.canonicalFile, request.steamRemoteDirectory)
        assertThrows(IllegalArgumentException::class.java) {
            ExternalWindowsLaunch.resolve(
                "steam:456", root, "game.exe", 64, steamAppId = 456, steamRemoteDirectory = remote,
            )
        }
    }
}
