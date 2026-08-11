package app.gamenative.externaldisplay

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsHomeSecondScreenInputRoutingTest {

    @Test
    fun companionControllerKeysIncludeNavigationAndActionsOnly() {
        assertTrue(KeyEvent.KEYCODE_DPAD_LEFT.isCompanionControllerKey())
        assertTrue(KeyEvent.KEYCODE_BUTTON_A.isCompanionControllerKey())
        assertTrue(KeyEvent.KEYCODE_BACK.isCompanionControllerKey())
        assertTrue(KeyEvent.KEYCODE_BUTTON_R1.isCompanionControllerKey())

        assertFalse(KeyEvent.KEYCODE_ENTER.isCompanionControllerKey())
        assertFalse(KeyEvent.KEYCODE_A.isCompanionControllerKey())
    }

    @Test
    fun gamepadConfirmUsesTheComposeActivationKey() {
        assertEquals(
            KeyEvent.KEYCODE_ENTER,
            normalizedCompanionComposeKeyCode(KeyEvent.KEYCODE_BUTTON_A),
        )
        assertEquals(
            KeyEvent.KEYCODE_DPAD_RIGHT,
            normalizedCompanionComposeKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT),
        )
    }

    @Test
    fun focusRecoveryOnlyReplaysSafeComposeNavigationKeys() {
        assertTrue(KeyEvent.KEYCODE_DPAD_CENTER.isCompanionComposeNavigationKey())
        assertTrue(KeyEvent.KEYCODE_ENTER.isCompanionComposeNavigationKey())
        assertTrue(KeyEvent.KEYCODE_DPAD_LEFT.isCompanionComposeNavigationKey())
        assertFalse(KeyEvent.KEYCODE_BUTTON_B.isCompanionComposeNavigationKey())
        assertFalse(KeyEvent.KEYCODE_BUTTON_START.isCompanionComposeNavigationKey())
    }

    @Test
    fun tabButtonsFallThroughWhenWorkspaceDoesNotProvideAnAction() {
        assertNull(companionTabAction(KeyEvent.KEYCODE_BUTTON_L1, null, null))
        assertNull(companionTabAction(KeyEvent.KEYCODE_BUTTON_R1, null, null))

        var direction = 0
        companionTabAction(
            KeyEvent.KEYCODE_BUTTON_L2,
            onPreviousTab = { direction = -1 },
            onNextTab = { direction = 1 },
        )?.invoke()
        assertEquals(-1, direction)

        companionTabAction(
            KeyEvent.KEYCODE_BUTTON_R2,
            onPreviousTab = { direction = -1 },
            onNextTab = { direction = 1 },
        )?.invoke()
        assertEquals(1, direction)
    }

    @Test
    fun gridNavigationMovesByRowsWithoutWrappingAcrossEdges() {
        assertEquals(5, nextLibraryControllerIndex(1, 12, KeyEvent.KEYCODE_DPAD_DOWN, 0, 4))
        assertEquals(1, nextLibraryControllerIndex(5, 12, KeyEvent.KEYCODE_DPAD_UP, 0, 4))
        assertEquals(4, nextLibraryControllerIndex(4, 12, KeyEvent.KEYCODE_DPAD_LEFT, 0, 4))
        assertEquals(7, nextLibraryControllerIndex(7, 12, KeyEvent.KEYCODE_DPAD_RIGHT, 0, 4))
    }

    @Test
    fun listAndCoverFlowUseTheirNaturalAxis() {
        assertEquals(4, nextLibraryControllerIndex(3, 10, KeyEvent.KEYCODE_DPAD_DOWN, 1, 4))
        assertNull(nextLibraryControllerIndex(3, 10, KeyEvent.KEYCODE_DPAD_RIGHT, 1, 4))
        assertEquals(4, nextLibraryControllerIndex(3, 10, KeyEvent.KEYCODE_DPAD_RIGHT, 2, 4))
        assertNull(nextLibraryControllerIndex(3, 10, KeyEvent.KEYCODE_DPAD_DOWN, 2, 4))
    }

    @Test
    fun analogNavigationUsesDominantAxisAndIgnoresStickDrift() {
        assertEquals(0.8f, dominantAxisValue(0.2f, 0.8f))
        assertEquals(-0.9f, dominantAxisValue(-0.9f, 0.3f))
        assertEquals(0, 0.54f.toControllerDirection())
        assertEquals(1, 0.55f.toControllerDirection())
        assertEquals(-1, (-0.55f).toControllerDirection())
    }

    @Test
    fun aynHatMotionIsAcceptedEvenWhenOemSourceBitsAreMissing() {
        assertTrue(isCompanionControllerMotion(InputDevice.SOURCE_UNKNOWN, hatX = 1f, hatY = 0f))
        assertTrue(isCompanionControllerMotion(InputDevice.SOURCE_JOYSTICK, hatX = 0f, hatY = 0f))
        assertFalse(isCompanionControllerMotion(InputDevice.SOURCE_TOUCHSCREEN, hatX = 0f, hatY = 0f))
    }

    @Test
    fun dialogLayerOverridesThePersistentGameWorkspace() {
        assertTrue(
            secondScreenOwnerPriority(DsHomeSecondScreen.Owner.DIALOG) >
                secondScreenOwnerPriority(DsHomeSecondScreen.Owner.GAME),
        )
    }

    @Test
    fun settingsWorkspaceOverridesGameCardOnTheSecondScreen() {
        assertTrue(
            secondScreenOwnerPriority(DsHomeSecondScreen.Owner.SETTINGS) >
                secondScreenOwnerPriority(DsHomeSecondScreen.Owner.GAME_CARD),
        )
    }
}
