package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class D3DGearCaptureFixTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun appendsOverrideForCaptureHookBesideExecutable() {
        folder.newFolder("bin")
        folder.newFile("bin/D3DGear64.DLL")

        assertEquals(
            "dxgi=n,b;d3dgear64=d",
            d3dGearCaptureOverride(folder.root.path, "bin\\game.exe", "dxgi=n,b"),
        )
    }

    @Test
    fun skipsMalformedPaths() {
        assertNull(d3dGearCaptureOverride(folder.root.path, "bin/\u0000game.exe", ""))
        assertNull(d3dGearCaptureOverride(folder.root.path + "\u0000", "bin/game.exe", ""))
    }

    @Test
    fun preservesExplicitOverrideAndSkipsOtherDirectories() {
        folder.newFolder("bin")
        folder.newFile("bin/d3dgear64.dll")
        folder.newFolder("other")

        assertNull(d3dGearCaptureOverride(folder.root.path, "other/game.exe", ""))
        assertNull(d3dGearCaptureOverride(folder.root.path, "bin/game.exe", "dxgi,*D3DGear64.DLL=n,b"))
    }
}
