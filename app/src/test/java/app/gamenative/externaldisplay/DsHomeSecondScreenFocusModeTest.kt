package app.gamenative.externaldisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsHomeSecondScreenFocusModeTest {

    @Test
    fun onlyInteractiveCompanionModesOwnControllerFocus() {
        assertTrue(DsHomeSecondScreen.Mode.GRID.ownsControllerFocus())
        assertTrue(DsHomeSecondScreen.Mode.CARD.ownsControllerFocus())
        assertTrue(DsHomeSecondScreen.Mode.SETTINGS.ownsControllerFocus())
        assertTrue(DsHomeSecondScreen.Mode.QUICK_MENU.ownsControllerFocus())

        assertFalse(DsHomeSecondScreen.Mode.DETAILS.ownsControllerFocus())
        assertFalse(DsHomeSecondScreen.Mode.QUICK_MENU_PASSIVE.ownsControllerFocus())
        assertFalse(DsHomeSecondScreen.Mode.GAME_DASHBOARD.ownsControllerFocus())
    }

    @Test
    fun openingMenuOverridesPassivePanelAndClosingRestoresIt() {
        for ((showMenu, passiveMode) in listOf(
            false to DsHomeSecondScreen.Mode.GAME_DASHBOARD,
            true to DsHomeSecondScreen.Mode.QUICK_MENU_PASSIVE,
        )) {
            assertEquals(passiveMode, secondScreenGameMode(menuActive = false, showPassiveMenu = showMenu))
            assertEquals(DsHomeSecondScreen.Mode.QUICK_MENU, secondScreenGameMode(menuActive = true, showPassiveMenu = showMenu))
        }
    }
}
