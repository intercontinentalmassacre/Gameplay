package app.gamenative.localgames

import com.winlator.core.KeyValueSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuranGameLaunchTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun forwardsDxvkSettingsAndPreservesThePocketProfileConfig() {
        val env = BuranGraphicsPackages.dxvkEnvironment(
            KeyValueSet("async=1,asyncCache=1,framerate=30"),
            "DXVK_ASYNC=0 DXVK_CONFIG=dxvk.maxMemoryBudget=256;d3d9.maxFrameRate=30 DXVK_STATE_CACHE=disable",
        )
        assertEquals("0", env.get("DXVK_ASYNC"))
        assertEquals("1", env.get("DXVK_GPLASYNCCACHE"))
        assertEquals("30", env.get("DXVK_FRAME_RATE"))
        assertEquals("dxvk.maxMemoryBudget=256;d3d9.maxFrameRate=30", env.get("DXVK_CONFIG"))
        assertEquals("disable", env.get("DXVK_STATE_CACHE"))

        val defaults = BuranGraphicsPackages.dxvkEnvironment(KeyValueSet("async=0,asyncCache=0"), "")
        assertFalse(defaults.has("DXVK_ASYNC"))
        assertFalse(defaults.has("DXVK_GPLASYNCCACHE"))
    }

    @Test
    fun resolvesTheSelectedNestedExecutableOnlyInsideTheGameRoot() {
        val root = files.newFolder("game").canonicalFile
        val executable = File(root, "bin/RallyHeads.EXE").apply {
            parentFile.mkdirs()
            writeText("test")
        }
        files.newFolder("game-other").resolve("Other.exe").writeText("test")
        File(root, "README.txt").writeText("test")

        assertEquals(executable.canonicalFile, resolveGameExecutable(root, "bin/RallyHeads.EXE"))
        assertEquals(executable.canonicalFile, resolveGameExecutable(root, "BIN/rallyheads.exe", ignoreCase = true))
        assertNull(resolveGameExecutable(root, "../game-other/Other.exe"))
        assertNull(resolveGameExecutable(root, "/bin/RallyHeads.EXE"))
        assertNull(resolveGameExecutable(root, "C:/bin/RallyHeads.EXE"))
        assertNull(resolveGameExecutable(root, "README.txt"))
    }
}
