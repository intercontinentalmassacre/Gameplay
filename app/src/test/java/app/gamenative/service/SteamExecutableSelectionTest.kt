package app.gamenative.service

import `in`.dragonbra.javasteam.types.FileData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamExecutableSelectionTest {
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
