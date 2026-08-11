package app.gamenative.externaldisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsHomeSecondScreenFocusModeTest {

    @Test
    fun onlyInteractiveCompanionModesOwnControllerFocus() {
        assertTrue(DsHomeSecondScreen.Mode.GRID.ownsControllerFocus())
        assertTrue(DsHomeSecondScreen.Mode.CARD.ownsControllerFocus())
        assertTrue(DsHomeSecondScreen.Mode.SETTINGS.ownsControllerFocus())

        assertFalse(DsHomeSecondScreen.Mode.DETAILS.ownsControllerFocus())
        assertFalse(DsHomeSecondScreen.Mode.QUICK_MENU.ownsControllerFocus())
        assertFalse(DsHomeSecondScreen.Mode.QUICK_MENU_PASSIVE.ownsControllerFocus())
        assertFalse(DsHomeSecondScreen.Mode.GAME_DASHBOARD.ownsControllerFocus())
    }
}
