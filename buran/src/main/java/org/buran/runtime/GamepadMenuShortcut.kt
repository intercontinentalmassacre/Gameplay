package org.buran.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent

/** A short press reaches the game, while a 700 ms hold opens the menu as in Gameplay. */
internal class GamepadMenuShortcut(
    private val canOpen: () -> Boolean,
    private val open: () -> Unit,
    private val forward: (KeyEvent) -> Unit,
) {
    private class Press(val event: KeyEvent, var hold: Runnable? = null)

    private val handler = Handler(Looper.getMainLooper())
    private val presses = mutableMapOf<Pair<Int, Int>, Press>()
    private val taps = mutableMapOf<Pair<Int, Int>, Runnable>()

    fun handle(event: KeyEvent, button: Int): Boolean {
        val key = event.deviceId to event.keyCode
        val previous = presses[key]
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (previous != null && event.repeatCount > 0) return true
                if (button == KeyEvent.KEYCODE_UNKNOWN || event.keyCode != button || event.repeatCount != 0 ||
                    !isController(event) || !canOpen()
                ) return false
                releaseTap(key)
                previous?.hold?.let(handler::removeCallbacks)
                val press = Press(KeyEvent(event))
                val hold = Runnable {
                    press.hold = null
                    if (canOpen()) open()
                }
                press.hold = hold
                presses[key] = press
                handler.postDelayed(hold, HOLD_MS)
                return true
            }
            KeyEvent.ACTION_UP -> {
                val press = presses.remove(key) ?: return false
                val hold = press.hold ?: return true
                handler.removeCallbacks(hold)
                if (event.isCanceled || !canOpen()) return true
                if (event.eventTime - press.event.eventTime >= HOLD_MS) {
                    open()
                } else {
                    val down = KeyEvent.changeTimeRepeat(press.event, SystemClock.uptimeMillis(), 0)
                    val release = Runnable {
                        taps.remove(key)
                        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                        forward(KeyEvent.changeTimeRepeat(up, SystemClock.uptimeMillis(), 0))
                    }
                    taps[key] = release
                    handler.postDelayed(release, TAP_MS)
                    forward(down)
                }
                return true
            }
        }
        return false
    }

    fun cancel() {
        for (press in presses.values) {
            press.hold?.let(handler::removeCallbacks)
            press.hold = null
        }
        taps.keys.toList().forEach(::releaseTap)
    }

    private fun releaseTap(key: Pair<Int, Int>) {
        val release = taps.remove(key) ?: return
        handler.removeCallbacks(release)
        release.run()
    }

    private fun isController(event: KeyEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true

    private companion object {
        const val HOLD_MS = 700L
        const val TAP_MS = 50L
    }
}
