package app.gamenative.shadercache

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FossilizeDriverIdentityTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun embeddedInstallKeyResolvesTheGameplayContainerId() {
        val debug = "app.gameplay.debug"
        assertEquals("STEAM_35140", BuranFossilizeBridge.gameplayContainerId(debug, "$debug:STEAM_35140"))
        assertNull(BuranFossilizeBridge.gameplayContainerId(debug, "app.gameplay:STEAM_35140"))
        assertNull(BuranFossilizeBridge.gameplayContainerId(debug, "$debug:"))
    }

    @Test fun driverAndCacheChangesInvalidateReplayState() {
        val icd = temp.newFile("driver.json").apply {
            writeText("""{"ICD":{"library_path":"driver.so"}}""")
        }
        val driver = temp.newFile("driver.so").apply { writeText("original") }
        val customDir = temp.newFolder("custom")
        val customDriver = customDir.resolve("vulkan.adreno.so").apply { writeText("custom") }
        val env = EnvVars().apply {
            put("VK_DRIVER_FILES", icd.absolutePath)
            put("ADRENOTOOLS_DRIVER_PATH", customDir.absolutePath)
        }
        fun key() = FossilizeManager.driverIdentity("profile", env)

        val original = key()
        icd.setLastModified(1_000L)
        assertEquals(original, key())

        val originalTime = driver.lastModified()
        driver.writeText("replaced")
        driver.setLastModified(originalTime)
        val replaced = key()
        assertNotEquals(original, replaced)

        customDriver.writeText("update")
        val customReplaced = key()
        assertNotEquals(replaced, customReplaced)

        env.put("XDG_CACHE_HOME", temp.newFolder("cache").absolutePath)
        val newCache = key()
        assertNotEquals(customReplaced, newCache)
        env.put("MESA_SHADER_CACHE_DIR", temp.newFolder("mesa").absolutePath)
        assertNotEquals(newCache, key())

        val directDriver = key()
        env.put("GN_VKSHIM_SYSTEM_DRIVER", "1")
        assertNotEquals(directDriver, key())
    }

    @Test fun nativeReplayExcludesExperimentalEglWithoutChangingTheGameEnvironment() {
        val egl = "/data/gamescope-experimental/lib/gl"
        val otherPaths = listOf("/data/gamescope-experimental/lib", "/data/imagefs/lib", "/system/lib64")
        val guestPaths = (listOf(egl) + otherPaths).joinToString(":")
        val guest = EnvVars().apply {
            put("LD_LIBRARY_PATH", guestPaths)
            put("VK_ICD_FILENAMES", "driver.json")
        }

        val replay = FossilizeManager.replayEnvironment(guest, egl)

        assertEquals(guestPaths, guest.get("LD_LIBRARY_PATH"))
        assertEquals(otherPaths.joinToString(":"), replay.get("LD_LIBRARY_PATH"))
        assertEquals("driver.json", replay.get("VK_ICD_FILENAMES"))
    }

    @Test fun embeddedReplayKeyIgnoresSessionDirectoriesButKeepsTheResolvedRendererAndCache() {
        val icd = temp.newFile("driver.json").apply {
            writeText("""{"ICD":{"library_path":"driver.so"}}""")
        }
        val driver = temp.newFile("driver.so").apply { writeText("selected driver") }
        val env = EnvVars().apply {
            put("VK_DRIVER_FILES", icd.absolutePath)
            put("MESA_SHADER_CACHE_DIR", temp.newFolder("cache").absolutePath)
            put("LD_LIBRARY_PATH", "/first/session:${driver.parent}")
            put("HOME", "/first/session")
        }
        fun key() = FossilizeManager.driverIdentity("buran", env, embedded = true)

        val original = key()
        env.put("HOME", "/next/session")
        env.put("LD_LIBRARY_PATH", "/next/session:${driver.parent}")
        assertEquals(original, key())

        driver.writeText("changed driver")
        val changedDriver = key()
        assertNotEquals(original, changedDriver)
        env.put("MESA_SHADER_CACHE_DIR", temp.newFolder("other-cache").absolutePath)
        assertNotEquals(changedDriver, key())
    }
}
