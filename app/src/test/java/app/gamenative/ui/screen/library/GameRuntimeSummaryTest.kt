package app.gamenative.ui.screen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GameRuntimeSummaryTest {

    @Test
    fun directXSummaryUsesTheActiveWrapperVersion() {
        val config = "version=3.1.1-0,vkd3dVersion=2.14.1"
        assertEquals("DXVK 3.1.1-0", directXSummary("dxvk", config))
        assertEquals("VKD3D 2.14.1", directXSummary("vkd3d", config))
        assertEquals("DXVK", directXSummary("dxvk", ""))
    }

    @Test
    fun glibcSummaryKeepsDriverFamilyAlongsideVersion() {
        assertEquals("Turnip · 25.3.0 R11", graphicsDriverSummary("turnip", "25.3.0 R11"))
    }

    @Test
    fun bionicSummaryReadsTheVersionSelectedByTheEditor() {
        val selectedVersion = graphicsDriverVersionSummary(
            containerVariant = "bionic",
            graphicsDriverVersion = "stale-glibc-value",
            graphicsDriverConfig = "version=turnip26.0.0_R8",
        )

        assertEquals(
            "Wrapper Gameplay · turnip26.0.0_R8",
            graphicsDriverSummary("wrapper-gamenative", selectedVersion),
        )
    }

    @Test
    fun summaryUsesDriverNameWhenNoVersionIsPinned() {
        assertEquals("VirGL", graphicsDriverSummary("virgl", ""))
    }
}
