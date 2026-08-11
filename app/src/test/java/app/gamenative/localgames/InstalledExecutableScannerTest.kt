package app.gamenative.localgames

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InstalledExecutableScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `finds installed games but excludes system and maintenance executables`() {
        val driveC = temporaryFolder.newFolder("drive_c")
        executable(driveC, "Program Files/Mafia/Mafia.exe")
        executable(driveC, "Program Files/Mafia/unins000.exe")
        executable(driveC, "Program Files/Mafia/GameUpdater.exe")
        executable(driveC, "windows/system32/notepad.exe")
        executable(driveC, "users/xuser/Temp/setup.exe")

        assertEquals(
            listOf("Program Files/Mafia/Mafia.exe"),
            InstalledExecutableScanner.findCandidates(driveC),
        )
    }

    @Test
    fun `prefers shipping binaries and returns each path once`() {
        val driveC = temporaryFolder.newFolder("drive_c")
        executable(driveC, "Program Files/Example/Launcher.exe")
        executable(driveC, "Program Files/Example/Binaries/Win64/Example-Win64-Shipping.exe")

        val candidates = InstalledExecutableScanner.findCandidates(driveC)

        assertEquals("Program Files/Example/Binaries/Win64/Example-Win64-Shipping.exe", candidates.first())
        assertEquals(candidates.distinct(), candidates)
        assertFalse(candidates.any { it.contains("windows", ignoreCase = true) })
    }

    @Test
    fun `rejects files that only have an exe extension`() {
        val driveC = temporaryFolder.newFolder("drive_c")
        executable(driveC, "Program Files/Example/Example.exe")
        File(driveC, "Program Files/Example/fake.exe").writeBytes(byteArrayOf(0x4d, 0x5a))

        assertEquals(
            listOf("Program Files/Example/Example.exe"),
            InstalledExecutableScanner.findCandidates(driveC),
        )
    }

    private fun executable(root: File, relativePath: String) {
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            val bytes = ByteBuffer.allocate(0x80 + 4 + 20 + 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put(0x4d.toByte())
            bytes.put(0x5a.toByte())
            bytes.position(0x3c)
            bytes.putInt(0x80)
            bytes.position(0x80)
            bytes.put(byteArrayOf(0x50, 0x45, 0, 0))
            bytes.position(0x80 + 4 + 20)
            bytes.putShort(0x10b.toShort())
            writeBytes(bytes.array())
        }
    }
}
