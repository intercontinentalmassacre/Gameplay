package app.gamenative.input

import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Call from the UI thread before forwarding physical controller events to the game. */
internal class GamepadQuickMenuShortcut(private val scope: CoroutineScope) {
    private data class Press(val down: KeyEvent, var hold: Job? = null)
    private data class Tap(val release: () -> Unit, val job: Job)

    private val presses = mutableMapOf<Pair<Int, Int>, Press>()
    private val taps = mutableMapOf<Pair<Int, Int>, Tap>()

    fun handle(
        event: KeyEvent,
        button: Int,
        canUse: () -> Boolean,
        openMenu: () -> Unit,
        forwardTap: (KeyEvent) -> Unit,
    ): Boolean {
        val key = event.deviceId to event.keyCode
        val press = presses[key]
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (press != null && event.repeatCount > 0) return true
                if (button == KeyEvent.KEYCODE_UNKNOWN || event.keyCode != button ||
                    event.repeatCount != 0 || !canUse()
                ) return false

                finishTap(key)
                presses.remove(key)?.hold?.cancel()
                val pending = Press(KeyEvent(event))
                presses[key] = pending
                pending.hold = scope.launch {
                    delay(HOLD_MS)
                    pending.hold = null
                    if (canUse()) openMenu()
                }
                true
            }
            KeyEvent.ACTION_UP -> {
                if (press == null) return false
                presses.remove(key)
                val hold = press.hold
                hold?.cancel()
                if (hold != null && !event.isCanceled && canUse()) {
                    if (event.eventTime - press.down.eventTime >= HOLD_MS) {
                        openMenu()
                    } else {
                        val now = SystemClock.uptimeMillis()
                        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, press.down.keyCode, 0,
                            press.down.metaState, press.down.deviceId, press.down.scanCode, press.down.flags, press.down.source)
                        val release = {
                            val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
                            forwardTap(KeyEvent.changeTimeRepeat(up, SystemClock.uptimeMillis(), 0))
                        }
                        val job = scope.launch {
                            delay(TAP_MS)
                            taps.remove(key)
                            release()
                        }
                        taps[key] = Tap(release, job)
                        forwardTap(down)
                    }
                }
                true
            }
            else -> false
        }
    }

    fun cancel() {
        presses.values.forEach {
            it.hold?.cancel()
            it.hold = null
        }
        taps.keys.toList().forEach(::finishTap)
    }

    fun removeDevice(deviceId: Int) {
        presses.keys.filter { it.first == deviceId }.forEach { presses.remove(it)?.hold?.cancel() }
        taps.keys.filter { it.first == deviceId }.forEach(::finishTap)
    }

    private fun finishTap(key: Pair<Int, Int>) {
        val tap = taps.remove(key) ?: return
        tap.job.cancel()
        tap.release()
    }

    companion object {
        const val HOLD_MS = 700L
        // Give a polling game time to observe the replayed button press.
        private const val TAP_MS = 50L
    }
}
