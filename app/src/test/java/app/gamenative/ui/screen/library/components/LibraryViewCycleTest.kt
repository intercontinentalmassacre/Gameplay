package app.gamenative.ui.screen.library.components

import app.gamenative.ui.enums.PaneType
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryViewCycleTest {

    @Test
    fun `cycle without dual screen skips DS_HOME`() {
        var view = PaneType.GRID_CAPSULE
        val visited = mutableListOf<PaneType>()
        repeat(6) {
            view = view.nextLibraryView(isDualScreen = false)
            visited += view
        }

        assertEquals(
            listOf(
                PaneType.INSTALLED_COMPACT,
                PaneType.GRID_HERO,
                PaneType.CAROUSEL,
                PaneType.GRID_CAPSULE,
                PaneType.INSTALLED_COMPACT,
                PaneType.GRID_HERO,
            ),
            visited,
        )
    }

    @Test
    fun `cycle with dual screen includes DS_HOME after GRID_HERO`() {
        val next = PaneType.GRID_HERO.nextLibraryView(isDualScreen = true)
        assertEquals(PaneType.DS_HOME, next)
        assertEquals(PaneType.CAROUSEL, next.nextLibraryView(isDualScreen = true))
    }

    @Test
    fun `legacy LIST migrates into the capsule grid`() {
        assertEquals(PaneType.GRID_CAPSULE, PaneType.LIST.nextLibraryView())
    }
}
