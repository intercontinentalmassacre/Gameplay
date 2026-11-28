package org.buran.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BionicSteamRuntimeTest {
    @get:Rule
    val files = TemporaryFolder()

    @Test
    fun legacyDxvkGetsAPersistentStateCacheOutsideTheGameDirectory() {
        val id = "b".repeat(32)
        val version = "1.11.1-sarek-pocket-d3d9-1"
        val dir = files.root.canonicalFile.resolve("shader-cache/$id/dxvk/$version/32")
        val env = BionicShaderCaches.dxvkStateCache(files.root, id, version, 32)
        assertTrue(dir.isDirectory)
        assertEquals("Z:" + dir.path.replace('/', '\\'), env["DXVK_STATE_CACHE_PATH"])
        assertEquals(env, BionicShaderCaches.dxvkStateCache(files.root, id, version, 32))
        assertTrue(BionicShaderCaches.dxvkStateCache(files.root, id, "2.7.1", 32).isEmpty())
    }

    @Test
    fun redirectIsAppendedWithoutLosingConfiguredPreloads() {
        assertEquals(
            "/guest/libandroid-sysvshm.so:/guest/extra.so:/guest/libredirect-bionic-wx.so",
            BionicSteamRuntime.guestPreloads(
                "/guest/libandroid-sysvshm.so /guest/extra.so:/guest/libandroid-sysvshm.so",
                "/guest/libredirect-bionic-wx.so",
            ),
        )
    }

    @Test
    fun duplicateRedirectAndMissingPreloadsAreHandled() {
        val redirect = "/guest/libredirect-bionic.so"
        assertEquals(redirect, BionicSteamRuntime.guestPreloads(redirect, redirect))
        assertEquals(redirect, BionicSteamRuntime.guestPreloads(null, redirect))
        assertEquals("", BionicSteamRuntime.guestPreloads(null, null))
    }

    @Test
    fun androidRelocationRunsBeforeTheWineWxPreload() {
        val wx = "/guest/libredirect-bionic-wx.so"
        val relocation = "/guest/libburan_redirect_bionic.so"
        val sysv = "/guest/libandroid-sysvshm.so"
        assertEquals("$sysv:$relocation:$wx", BionicSteamRuntime.guestPreloads("$wx:$sysv:$relocation", wx, relocation))
    }

    @Test
    fun rendererInterposerIsFirstAndOnlyAddedToTheGamePreload() {
        val renderer = "/guest/libburan_dri3bridge.so"
        val relocation = "/guest/libburan_redirect_bionic.so"
        val sysv = "/guest/libandroid-sysvshm.so"
        assertEquals("$renderer:$sysv:$relocation",
            BionicSteamRuntime.guestPreloads("$sysv:$renderer", null, relocation, renderer))
        assertEquals("$sysv:$relocation", BionicSteamRuntime.guestPreloads(sysv, null, relocation))
    }
}
