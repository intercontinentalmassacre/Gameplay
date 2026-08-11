package app.gamenative.ui.screen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class GameRuntimeSummaryTest {

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
