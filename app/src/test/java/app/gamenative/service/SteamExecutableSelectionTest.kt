package app.gamenative.service

import `in`.dragonbra.javasteam.types.FileData
import app.gamenative.utils.ExecutableSelectionUtils
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamExecutableSelectionTest {
    @get:Rule val install = TemporaryFolder()

    @Test
    fun staleExecutableRequiresAnInstalledReplacement() {
        install.newFolder("Bin")
        install.newFile("Bin/New.exe")
        fun resolve(fallback: String) =
            ExecutableSelectionUtils.resolveConfiguredExecutable(install.root, "Old.exe") { fallback }
        assertEquals("bin\\new.exe", resolve("bin\\new.exe"))
        assertEquals("Old.exe", resolve("Missing.exe"))
        assertEquals("Old.exe", resolve(""))
    }

    @Test
    fun customExecutablePathsSurviveDetection() {
        install.newFile("Game.exe")
        for (choice in listOf("\"game.exe\"", "D:\\Other.exe", "\\\\share\\other.exe", "/mnt/other.exe")) {
            assertEquals(choice, ExecutableSelectionUtils.resolveConfiguredExecutable(install.root, choice) {
                error("Unexpected automatic detection")
            })
        }
    }

    private fun exe(path: String, size: Long) = FileData().apply {
        fileName = path
        totalSize = size
    }

    @Test
    fun `Batman selects the game binary over its larger PhysX installer`() {
        val game = exe("Binaries/ShippingPC-BmGame.exe", 38_758_728)
        val installer = exe("redist/PhysX_9.08.14_9.09.0814_SystemSoftware.exe", 41_212_184)
        val worker = exe("Binaries/UE3ShaderCompileWorker.exe", 62_784)
        assertEquals(game, SteamService.choosePrimaryExe(listOf(worker, installer, game), "batman arkham asylum goty"))
    }

    @Test
    fun `installer directories are excluded for both path separators`() {
        val files = listOf(
            exe("_CommonRedist/DirectX/Jun2010/DXSETUP.exe", 42_000_000),
            exe("Support\\Tools\\Repair.exe", 5_000_000),
        )
        assertNull(SteamService.choosePrimaryExe(files, "example game"))
    }
}
