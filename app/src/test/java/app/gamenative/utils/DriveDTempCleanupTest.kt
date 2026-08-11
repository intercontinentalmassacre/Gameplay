package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DriveDTempCleanupTest {

    private lateinit var downloadsDir: File

    @Before
    fun setUp() {
        downloadsDir = File.createTempFile("downloads_test_", null)
        downloadsDir.delete()
        downloadsDir.mkdirs()
    }

    @Test
    fun `empty hex dirs are removed, normal folders are kept`() {
        File(downloadsDir, "00c7a5ff704ac95ee0").mkdirs()
        File(downloadsDir, "My Games").mkdirs()

        val deleted = DriveDTempCleanup.sweep(downloadsDir)

        assertEquals(1, deleted)
        assertTrue(!File(downloadsDir, "00c7a5ff704ac95ee0").exists())
        assertTrue(File(downloadsDir, "My Games").isDirectory)
    }

    @Test
    fun `hex dirs with installer payload are removed, user content is kept`() {
        val redistDir = File(downloadsDir, "00d5979c685e42f8c9")
        redistDir.mkdirs()
        File(redistDir, "vc_redist.x64.exe").writeText("payload")
        val userDir = File(downloadsDir, "a1b2c3d4e5f60718")
        userDir.mkdirs()
        File(userDir, "savegame.sav").writeText("save")

        val deleted = DriveDTempCleanup.sweep(downloadsDir)

        assertEquals(1, deleted)
        assertTrue(!redistDir.exists())
        assertTrue(userDir.isDirectory)
    }

    @Test
    fun `short and non-hex names are ignored`() {
        File(downloadsDir, "abcdef").mkdirs()
        File(downloadsDir, "00c7a5ff704ac95ee0zz").mkdirs()

        val deleted = DriveDTempCleanup.sweep(downloadsDir)

        assertEquals(0, deleted)
    }
}
