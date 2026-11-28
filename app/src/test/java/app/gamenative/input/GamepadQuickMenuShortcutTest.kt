package app.gamenative.input

import android.view.InputDevice
import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GamepadQuickMenuShortcutTest {
    @Test
    fun tapReplaysButtonForPollingGame() = runTest {
        val shortcut = GamepadQuickMenuShortcut(this)
        val forwarded = mutableListOf<Int>()
        var opens = 0
        fun send(event: KeyEvent) = shortcut.handle(event, BUTTON, { true }, { opens++ }) {
            forwarded.add(it.action)
        }

        assertTrue(send(event(KeyEvent.ACTION_DOWN)))
        assertTrue(forwarded.isEmpty())
        assertTrue(send(event(KeyEvent.ACTION_UP, time = 80)))
        assertEquals(listOf(KeyEvent.ACTION_DOWN), forwarded)
        advanceTimeBy(51)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), forwarded)
        assertEquals(0, opens)
    }

    @Test
    fun holdOpensOnceAndConsumesReleaseAcrossOverlay() = runTest {
        val shortcut = GamepadQuickMenuShortcut(this)
        var menuOpen = false
        var opens = 0
        fun send(event: KeyEvent) = shortcut.handle(event, BUTTON, { !menuOpen }, {
            opens++
            menuOpen = true
        }) {
            error("Hold must not be forwarded")
        }

        assertTrue(send(event(KeyEvent.ACTION_DOWN)))
        advanceTimeBy(GamepadQuickMenuShortcut.HOLD_MS)
        runCurrent()
        assertTrue(menuOpen)
        assertTrue(send(event(KeyEvent.ACTION_DOWN, repeat = 1)))
        shortcut.cancel()
        menuOpen = false
        assertTrue(send(event(KeyEvent.ACTION_UP, time = 800)))
        assertEquals(1, opens)
    }

    @Test
    fun disabledAndUnrelatedButtonsPassThrough() = runTest {
        val shortcut = GamepadQuickMenuShortcut(this)
        assertFalse(shortcut.handle(event(KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_UNKNOWN, { true }, {}, {}))
        assertFalse(shortcut.handle(event(KeyEvent.ACTION_DOWN), KeyEvent.KEYCODE_BUTTON_A, { true }, {}, {}))
    }

    @Test
    fun overlayCancelsPendingHoldAndReleasesReplayedTap() = runTest {
        val shortcut = GamepadQuickMenuShortcut(this)
        val forwarded = mutableListOf<Int>()
        var opens = 0
        fun send(event: KeyEvent) = shortcut.handle(event, BUTTON, { true }, { opens++ }) {
            forwarded.add(it.action)
        }

        assertTrue(send(event(KeyEvent.ACTION_DOWN)))
        assertTrue(send(event(KeyEvent.ACTION_UP, time = 50)))
        assertTrue(send(event(KeyEvent.ACTION_DOWN, deviceId = 2)))
        shortcut.cancel()
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), forwarded)
        assertTrue(send(event(KeyEvent.ACTION_UP, time = 500, deviceId = 2)))
        advanceTimeBy(GamepadQuickMenuShortcut.HOLD_MS + 1)
        assertEquals(0, opens)
        assertEquals(2, forwarded.size)
    }

    @Test
    fun releaseFromAnotherControllerCannotCompleteHold() = runTest {
        val shortcut = GamepadQuickMenuShortcut(this)
        var opens = 0
        fun send(event: KeyEvent) = shortcut.handle(event, BUTTON, { true }, { opens++ }, {})

        assertTrue(send(event(KeyEvent.ACTION_DOWN)))
        assertFalse(send(event(KeyEvent.ACTION_UP, deviceId = 2)))
        shortcut.removeDevice(1)
        advanceTimeBy(GamepadQuickMenuShortcut.HOLD_MS + 1)
        assertEquals(0, opens)
    }

    private fun event(action: Int, time: Long = 0, repeat: Int = 0, deviceId: Int = 1) =
        KeyEvent(0, time, action, BUTTON, repeat, 0, deviceId, 0, 0, InputDevice.SOURCE_GAMEPAD)

    private companion object {
        const val BUTTON = KeyEvent.KEYCODE_BUTTON_START
    }
}
