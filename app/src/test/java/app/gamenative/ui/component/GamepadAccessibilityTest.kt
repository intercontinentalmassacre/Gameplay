package app.gamenative.ui.component

import app.gamenative.R
import app.gamenative.ui.util.ControllerFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GamepadAccessibilityTest {
    @Test
    fun faceButtonLabelsFollowTheRenderedControllerFamily() {
        assertEquals(R.string.button_a, GamepadButton.A.accessibilityLabelFor(ControllerFamily.XBOX))
        assertEquals(R.string.gamepad_ps_cross, GamepadButton.A.accessibilityLabelFor(ControllerFamily.PLAYSTATION))
        assertEquals(R.string.button_b, GamepadButton.B.accessibilityLabelFor(ControllerFamily.XBOX))
        assertEquals(R.string.gamepad_ps_circle, GamepadButton.B.accessibilityLabelFor(ControllerFamily.PLAYSTATION))
    }

    @Test
    fun shoulderLabelsDoNotAnnounceXboxNamesForPlayStationGlyphs() {
        assertNotEquals(
            GamepadButton.LB.accessibilityLabelFor(ControllerFamily.XBOX),
            GamepadButton.LB.accessibilityLabelFor(ControllerFamily.PLAYSTATION),
        )
        assertEquals(
            R.string.gamepad_ps_l1,
            GamepadButton.LB.accessibilityLabelFor(ControllerFamily.PLAYSTATION),
        )
    }
}
