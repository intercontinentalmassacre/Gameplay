package org.buran.runtime

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.ResultReceiver
import com.droiddeck.launcher.wayland.WaylandCompositor as Compositor
import kotlin.math.roundToInt

private const val ARGUMENTS = "args"
private const val LIFETIME = "lifetime"
private const val ATTACH = 0
private const val MOUSE_ABSOLUTE = 1
private const val MOUSE_RELATIVE = 2
private const val MOUSE_BUTTON = 3
private const val MOUSE_WHEEL = 4
private const val KEY = 5
private const val TOUCH = 6
private const val RESET = 7
private const val DISCONNECT = 8

/** The controller-profile input endpoint for one Buran game session. */
class GameplayInputClient private constructor(private val receiver: ResultReceiver) : AutoCloseable {
    /** Coordinates use a 1920×1080 space. Pointer IDs are in 0..31. */
    enum class TouchAction(internal val wire: Int) { DOWN(0), MOVE(1), UP(2) }

    private val lifetime = Binder()
    private var closed = false

    init {
        receiver.send(ATTACH, Bundle().apply { putBinder(LIFETIME, lifetime) })
    }

    fun mouseAbsolute(x: Int, y: Int) = send(MOUSE_ABSOLUTE, x, y)
    fun mouseRelative(dx: Int, dy: Int) = send(MOUSE_RELATIVE, dx, dy)
    fun mouseButton(evdev: Int, pressed: Boolean) = send(MOUSE_BUTTON, evdev, if (pressed) 1 else 0)
    fun mouseWheel(vertical: Int, horizontal: Int = 0) = send(MOUSE_WHEEL, vertical, horizontal)
    fun key(evdev: Int, pressed: Boolean) = send(KEY, evdev, if (pressed) 1 else 0)
    fun touch(action: TouchAction, pointerId: Int, x: Int, y: Int) = send(TOUCH, action.wire, pointerId, x, y)
    fun cancelTouches() = send(TOUCH, 3, -1, 0, 0)
    fun reset() = send(RESET)

    @Synchronized
    private fun send(code: Int, vararg arguments: Int) {
        if (!closed) receiver.send(code, Bundle().apply { putIntArray(ARGUMENTS, arguments) })
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        receiver.send(DISCONNECT, null)
    }

    companion object {
        const val EVENT = "input_ready"
        internal const val RECEIVER = "inputReceiver"
        internal const val PROTOCOL = "inputProtocol"
        internal const val VERSION = 1

        @Suppress("DEPRECATION")
        fun fromEvent(event: Bundle): GameplayInputClient? {
            if (event.getString("event") != EVENT || event.getInt(PROTOCOL) != VERSION) return null
            return event.getParcelable<ResultReceiver>(RECEIVER)?.let(::GameplayInputClient)
        }
    }
}

/** Owns all presses and contacts contributed by the profile. Use on the main thread. */
internal class GameplayInputChannel(private val acceptsInput: () -> Boolean) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val buttons = mutableSetOf<Int>()
    private val keys = mutableSetOf<Int>()
    private val contacts = mutableSetOf<Int>()
    private var active = false
    private var closed = false
    private var lifetime: IBinder? = null
    private val senderDeath = IBinder.DeathRecipient { handler.post { close() } }
    val receiver: ResultReceiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (closed) return
            when {
                resultCode == ATTACH -> attach(resultData?.getBinder(LIFETIME))
                lifetime == null -> return
                resultCode == DISCONNECT -> close()
                resultCode == RESET -> release()
                active && acceptsInput() -> resultData?.getIntArray(ARGUMENTS)?.let { handle(resultCode, it) }
            }
        }
    }

    fun companionReceiver(
        requestStop: () -> Unit,
        setMouseDisabled: (Boolean) -> Unit,
        setDirectTouch: (Boolean) -> Unit,
        restart: () -> Unit,
        setOutputFps: (Boolean) -> Unit,
        setFpsLimit: (Int) -> Unit,
        setPerformanceHud: (Bundle) -> Unit,
        setMangoHud: (Boolean) -> Unit,
        setFramegen: (Boolean) -> Unit,
    ): ResultReceiver = object : ResultReceiver(handler) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            if (closed) return
            when (resultCode) {
                4 -> requestStop()
                5 -> {
                    attach(resultData?.getBinder(LIFETIME))
                    release()
                }
                7 -> if (resultData?.containsKey("disabled") == true) setMouseDisabled(resultData.getBoolean("disabled"))
                8 -> if (resultData?.containsKey("direct") == true) setDirectTouch(resultData.getBoolean("direct"))
                9 -> restart()
                10 -> if (resultData?.containsKey("enabled") == true) setOutputFps(resultData.getBoolean("enabled"))
                11 -> {
                    val fps = resultData?.getInt("fps", -1) ?: -1
                    if (fps == 0 || fps in 15..120) setFpsLimit(fps)
                }
                12 -> if (resultData?.containsKey("enabled") == true) setPerformanceHud(resultData)
                13 -> if (resultData?.containsKey("enabled") == true) setMangoHud(resultData.getBoolean("enabled"))
                14 -> if (resultData?.containsKey("enabled") == true) setFramegen(resultData.getBoolean("enabled"))
                else -> {
                    if (!active || !acceptsInput() || resultData == null) return
                    when (resultCode) {
                        1 -> {
                            val dx = relativeDistance(resultData.getFloat("dx"))
                            val dy = relativeDistance(resultData.getFloat("dy"))
                            if (dx != 0 || dy != 0) Compositor.nativeSendSceneInput(6, dx, dy)
                        }
                        2 -> handle(MOUSE_BUTTON, intArrayOf(resultData.getInt("button"), if (resultData.getBoolean("down")) 1 else 0))
                        3 -> handle(MOUSE_WHEEL, intArrayOf(resultData.getInt("steps"), 0))
                        6 -> handle(KEY, intArrayOf(resultData.getInt("key"), if (resultData.getBoolean("down")) 1 else 0))
                    }
                }
            }
        }
    }

    private fun attach(token: IBinder?) {
        if (token == null || lifetime != null) return
        try {
            token.linkToDeath(senderDeath, 0)
            lifetime = token
        } catch (_: RemoteException) {
            close()
        }
    }

    fun setActive(enabled: Boolean) {
        active = enabled && !closed
        if (!active) release()
    }

    private fun handle(code: Int, args: IntArray) {
        if (code == TOUCH) {
            if (args.size == 4) touch(args[0], args[1], args[2], args[3])
            return
        }
        if (args.size != 2) return
        val (a, b) = args
        when (code) {
            MOUSE_ABSOLUTE -> Compositor.nativeSendPointer(1, inputX(a), inputY(b))
            MOUSE_RELATIVE -> if (a != 0 || b != 0) Compositor.nativeSendSceneInput(6, scale(a, 256), scale(b, 256))
            MOUSE_BUTTON -> if (a in 0x110..0x117 && transition(buttons, a, b)) Compositor.nativeSendSceneInput(3, a, b)
            MOUSE_WHEEL -> if (b != 0) Compositor.nativeSendSceneInput(11, scale(a, 120), scale(b, 120))
                else if (a != 0) Compositor.nativeSendSceneInput(4, a.coerceIn(-120, 120), 0)
            KEY -> if (a in 1..0x2ff && transition(keys, a, b)) Compositor.nativeSendKey(a, b)
        }
    }

    private fun touch(action: Int, id: Int, x: Int, y: Int) {
        if (action == 3) {
            cancelTouches()
            return
        }
        if (id in 0..31 && when (action) {
            0 -> contacts.add(id)
            1 -> id in contacts
            2 -> contacts.remove(id)
            else -> false
        }) Compositor.nativeSendTouch(action, id, inputX(x), inputY(y))
    }

    private fun cancelTouches() {
        if (contacts.isEmpty()) return
        Compositor.nativeSendTouch(3, -1, 0, 0)
        contacts.clear()
    }

    private fun release() {
        for (button in buttons) Compositor.nativeSendSceneInput(3, button, 0)
        for (key in keys) Compositor.nativeSendKey(key, 0)
        buttons.clear()
        keys.clear()
        cancelTouches()
    }

    override fun close() {
        if (closed) return
        closed = true
        setActive(false)
        lifetime?.let { runCatching { it.unlinkToDeath(senderDeath, 0) } }
        lifetime = null
    }

    private fun transition(active: MutableSet<Int>, code: Int, state: Int) = when (state) {
        0 -> active.remove(code)
        1 -> active.add(code)
        else -> false
    }

    private fun scale(value: Int, factor: Int): Int =
        value.coerceIn(Int.MIN_VALUE / factor, Int.MAX_VALUE / factor) * factor

    private fun relativeDistance(pixels: Float): Int =
        if (pixels.isFinite()) (pixels * 256).roundToInt() else 0

    private fun inputX(value: Int) = value.coerceIn(0, 1919)
    private fun inputY(value: Int) = value.coerceIn(0, 1079)
}
