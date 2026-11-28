package org.buran.runtime.display

import org.buran.runtime.buranFilesDir
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.droiddeck.launcher.wayland.WaylandCompositor as Compositor
import org.buran.runtime.TurnipDrivers
import java.io.File
import java.nio.file.Files

/** One SurfaceView adapter for DroidDeck's process-wide Wayland host. Use on the main thread. */
internal class GamescopeHostView(
    context: Context,
    private val onReady: (GamescopeHostView) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val outputWidth: Int = 960,
    private val outputHeight: Int = 540,
    private val driverOverride: () -> TurnipDrivers.Driver? = { null },
    framegen2x: Boolean = false,
    private val onFramegenStatus: (FramegenStatus) -> Unit = {},
) : SurfaceView(context), SurfaceHolder.Callback, AutoCloseable {
    data class FramegenStatus(val requested: Boolean, val active: Boolean = false, val error: String? = null)

    val runtimeDirectory = Files.createTempDirectory(context.buranFilesDir.toPath(), "gs-").toFile()
    val displayName = "wayland-0"
    val socketPath: File get() = runtimeDirectory.resolve(displayName)

    fun setFpsLimit(fps: Int) = Compositor.nativeSetFpsLimit(fps)

    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var attached = false
    private var closed = false
    private var ready = false
    private var surfaceAvailable = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    @Volatile private var framegen: FramegenOutput? = null
    fun sampleGeneratedFps(now: Long): Int? = framegen?.sampleGeneratedFps(now)
    private var drainingFramegen = false
    val framegenAvailable = FramegenOutput.isAvailable(context)
    var framegenStatus = FramegenStatus(requested = framegen2x && framegenAvailable)
        private set
    var mouse: StandaloneMouse? = null
    private val pressedKeys = mutableSetOf<Int>()
    private var inputEnabled = true
    private var mouseDisabled = false
    private var pointerId = -1
    private var pointerX = 0f
    private var pointerY = 0f
    private var readyDeadline = 0L

    private val readiness = Runnable { checkReadiness() }
    private val framegenDeadline = Runnable {
        val output = framegen
        if (output != null && !framegenStatus.active) {
            framegenError(output, IllegalStateException("Gamescope did not produce a frame for VKFG"))
        }
    }
    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!attached) return
            Compositor.nativeVsync(frameTimeNanos)
            choreographer.postFrameCallback(this)
        }
    }

    init {
        require(outputWidth > 0 && outputHeight > 0)
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        surfaceAvailable = true
        if (!framegenRequested()) connectSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!surfaceAvailable || width <= 0 || height <= 0) return
        val resized = width != surfaceWidth || height != surfaceHeight
        surfaceWidth = width
        surfaceHeight = height
        if (resized && framegen != null) retireFramegen()
        else if (!attached && !drainingFramegen) connectSurface()
    }

    private fun connectSurface() {
        if (closed || !surfaceAvailable || drainingFramegen || !holder.surface.isValid) return
        val screen = holder.surface
        if (framegenRequested()) {
            if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            try {
                framegen = FramegenOutput(
                    context.applicationContext, screen, outputWidth, outputHeight,
                    ::framegenActive, ::framegenError,
                )
            } catch (error: Exception) {
                publishFramegenError(error)
            }
        }
        attachSurface(framegen?.surface ?: screen)
        if (ready && framegen != null) {
            handler.removeCallbacks(framegenDeadline)
            handler.postDelayed(framegenDeadline, 5_000L)
        }
    }

    private fun attachSurface(surface: Surface) {
        try {
            check(owner == null || owner === this) { "Another Gamescope output is attached" }
            if (attached) {
                Compositor.nativeSetSurface(surface)
                beginReadiness()
                return
            }
            Compositor.nativeSetOutputSize(outputWidth, outputHeight)
            if (!started) {
                check(runtimeDirectory.isDirectory || runtimeDirectory.mkdirs()) {
                    "Cannot create the Gamescope runtime directory"
                }
                Os.chmod(runtimeDirectory.path, 0b111000000)
                val driver = driverOverride() ?: TurnipDrivers.selected(context)
                copyAsset("keymap.xkb", runtimeDirectory.resolve("keymap.xkb"))
                Compositor.nativeStartWithSurface(
                    surface,
                    runtimeDirectory.path,
                    driver.directory.path + "/",
                    driver.libraryName,
                    context.applicationInfo.nativeLibraryDir,
                    false,
                )
                started = true
            } else {
                Compositor.nativeSetSurface(surface)
            }
            owner = this
            attached = true
            if (inputEnabled) requestFocus()
            choreographer.postFrameCallback(vsync)
            beginReadiness()
        } catch (error: Exception) {
            framegen?.let { framegenError(it, error) } ?: onError(error)
        } catch (error: LinkageError) {
            framegen?.let { framegenError(it, error) } ?: onError(error)
        }
    }

    private fun beginReadiness() {
        if (ready) return
        handler.removeCallbacks(readiness)
        readyDeadline = SystemClock.uptimeMillis() + 10_000L
        handler.post(readiness)
    }

    private fun framegenError(failed: FramegenOutput, error: Throwable) {
        if (framegen !== failed) return
        publishFramegenError(error)
        retireFramegen()
    }

    private fun framegenActive(output: FramegenOutput) {
        if (framegen !== output) return
        handler.removeCallbacks(framegenDeadline)
        updateFramegenStatus(framegenStatus.copy(active = true))
    }

    private fun publishFramegenError(error: Throwable) {
        Log.w("BuranFramegen", "Using direct Gamescope output", error)
        updateFramegenStatus(framegenStatus.copy(active = false, error = error.localizedMessage ?: error.javaClass.simpleName))
    }

    fun setFramegenEnabled(enabled: Boolean) {
        if (closed || !framegenAvailable ||
            (framegenStatus.requested == enabled && framegenStatus.error == null)
        ) return
        updateFramegenStatus(FramegenStatus(requested = enabled, active = framegenStatus.active))
        if (!enabled) retireFramegen()
        else connectSurface()
    }

    private fun framegenRequested() = framegenStatus.requested && framegenStatus.error == null

    private fun updateFramegenStatus(status: FramegenStatus) {
        if (status == framegenStatus) return
        framegenStatus = status
        if (!closed) onFramegenStatus(status)
    }

    private fun retireFramegen() {
        val output = framegen ?: return
        framegen = null
        drainingFramegen = true
        handler.removeCallbacks(readiness)
        handler.removeCallbacks(framegenDeadline)
        try {
            if (attached) Compositor.nativeSetSurface(null)
        } finally {
            output.close {
                drainingFramegen = false
                updateFramegenStatus(framegenStatus.copy(active = false))
                connectSurface()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        surfaceWidth = 0
        surfaceHeight = 0
        try {
            detachSurface()
        } finally {
            retireFramegen()
        }
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed) return
        closed = true
        holder.removeCallback(this)
        surfaceDestroyed(holder)
    }

    private fun detachSurface() {
        if (!attached) return
        releaseInput()
        attached = false
        handler.removeCallbacks(readiness)
        choreographer.removeFrameCallback(vsync)
        Compositor.nativeSetSurface(null)
        if (owner === this) owner = null
    }

    private fun copyAsset(name: String, destination: File) {
        context.assets.open("gamescope-host/$name").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun checkReadiness() {
        if (!attached || closed || ready) return
        val fault = Compositor.nativeCompositorFault()
        if (!fault.isNullOrEmpty()) {
            val error = IllegalStateException(fault)
            framegen?.let { framegenError(it, error) } ?: onError(error)
            return
        }
        val listening = try {
            OsConstants.S_ISSOCK(Os.stat(socketPath.path).st_mode)
        } catch (_: ErrnoException) {
            false
        }
        if (listening && Compositor.nativeWaitSurface(1)) {
            ready = true
            onReady(this)
        } else if (SystemClock.uptimeMillis() < readyDeadline) {
            handler.postDelayed(readiness, 20)
        } else {
            val error = IllegalStateException("Gamescope Wayland socket or Android surface did not become ready")
            framegen?.let { framegenError(it, error) } ?: onError(error)
        }
    }

    /** Actions use Android's DOWN, UP, and MOVE constants. Coordinates are View pixels. */
    fun sendPointer(action: Int, x: Float, y: Float) {
        if (!attached || width < 1 || height < 1) return
        val nativeAction = when (action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> return
        }
        val scaledX = (x / width * INPUT_WIDTH).toInt().coerceIn(0, INPUT_WIDTH - 1)
        val scaledY = (y / height * INPUT_HEIGHT).toInt().coerceIn(0, INPUT_HEIGHT - 1)
        Compositor.nativeSendPointer(nativeAction, scaledX, scaledY)
    }

    fun sendKey(evdev: Int, pressed: Boolean) {
        if (!attached || evdev <= 0) return
        if (pressed) pressedKeys.add(evdev) else pressedKeys.remove(evdev)
        Compositor.nativeSendKey(evdev, if (pressed) 1 else 0)
    }

    // Android key events synthesized without a hardware scan code use these evdev positions.
    private fun fallbackEvdevKey(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_A -> 30
        KeyEvent.KEYCODE_B -> 48
        KeyEvent.KEYCODE_C -> 46
        KeyEvent.KEYCODE_D -> 32
        KeyEvent.KEYCODE_E -> 18
        KeyEvent.KEYCODE_F -> 33
        KeyEvent.KEYCODE_G -> 34
        KeyEvent.KEYCODE_H -> 35
        KeyEvent.KEYCODE_I -> 23
        KeyEvent.KEYCODE_J -> 36
        KeyEvent.KEYCODE_K -> 37
        KeyEvent.KEYCODE_L -> 38
        KeyEvent.KEYCODE_M -> 50
        KeyEvent.KEYCODE_N -> 49
        KeyEvent.KEYCODE_O -> 24
        KeyEvent.KEYCODE_P -> 25
        KeyEvent.KEYCODE_Q -> 16
        KeyEvent.KEYCODE_R -> 19
        KeyEvent.KEYCODE_S -> 31
        KeyEvent.KEYCODE_T -> 20
        KeyEvent.KEYCODE_U -> 22
        KeyEvent.KEYCODE_V -> 47
        KeyEvent.KEYCODE_W -> 17
        KeyEvent.KEYCODE_X -> 45
        KeyEvent.KEYCODE_Y -> 21
        KeyEvent.KEYCODE_Z -> 44
        KeyEvent.KEYCODE_0 -> 11
        KeyEvent.KEYCODE_1 -> 2
        KeyEvent.KEYCODE_2 -> 3
        KeyEvent.KEYCODE_3 -> 4
        KeyEvent.KEYCODE_4 -> 5
        KeyEvent.KEYCODE_5 -> 6
        KeyEvent.KEYCODE_6 -> 7
        KeyEvent.KEYCODE_7 -> 8
        KeyEvent.KEYCODE_8 -> 9
        KeyEvent.KEYCODE_9 -> 10
        KeyEvent.KEYCODE_F1 -> 59
        KeyEvent.KEYCODE_F2 -> 60
        KeyEvent.KEYCODE_F3 -> 61
        KeyEvent.KEYCODE_F4 -> 62
        KeyEvent.KEYCODE_F5 -> 63
        KeyEvent.KEYCODE_F6 -> 64
        KeyEvent.KEYCODE_F7 -> 65
        KeyEvent.KEYCODE_F8 -> 66
        KeyEvent.KEYCODE_F9 -> 67
        KeyEvent.KEYCODE_F10 -> 68
        KeyEvent.KEYCODE_F11 -> 87
        KeyEvent.KEYCODE_F12 -> 88
        KeyEvent.KEYCODE_SHIFT_LEFT -> 42
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 54
        KeyEvent.KEYCODE_CTRL_LEFT -> 29
        KeyEvent.KEYCODE_CTRL_RIGHT -> 97
        KeyEvent.KEYCODE_ALT_LEFT -> 56
        KeyEvent.KEYCODE_ALT_RIGHT -> 100
        KeyEvent.KEYCODE_META_LEFT -> 125
        KeyEvent.KEYCODE_META_RIGHT -> 126
        KeyEvent.KEYCODE_CAPS_LOCK -> 58
        KeyEvent.KEYCODE_SPACE -> 57
        KeyEvent.KEYCODE_DEL -> 14
        KeyEvent.KEYCODE_FORWARD_DEL -> 111
        KeyEvent.KEYCODE_GRAVE -> 41
        KeyEvent.KEYCODE_MINUS -> 12
        KeyEvent.KEYCODE_EQUALS -> 13
        KeyEvent.KEYCODE_LEFT_BRACKET -> 26
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 27
        KeyEvent.KEYCODE_BACKSLASH -> 43
        KeyEvent.KEYCODE_SEMICOLON -> 39
        KeyEvent.KEYCODE_APOSTROPHE -> 40
        KeyEvent.KEYCODE_COMMA -> 51
        KeyEvent.KEYCODE_PERIOD -> 52
        KeyEvent.KEYCODE_SLASH -> 53
        KeyEvent.KEYCODE_MOVE_HOME -> 102
        KeyEvent.KEYCODE_MOVE_END -> 107
        KeyEvent.KEYCODE_PAGE_UP -> 104
        KeyEvent.KEYCODE_PAGE_DOWN -> 109
        KeyEvent.KEYCODE_INSERT -> 110
        else -> 0
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(event)
        if (inputEnabled && attached && mouse?.key(event) == true) return true
        val evdev = when (event.keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> 1
            KeyEvent.KEYCODE_ENTER -> 28
            KeyEvent.KEYCODE_TAB -> 15
            KeyEvent.KEYCODE_DPAD_UP -> 103
            KeyEvent.KEYCODE_DPAD_LEFT -> 105
            KeyEvent.KEYCODE_DPAD_RIGHT -> 106
            KeyEvent.KEYCODE_DPAD_DOWN -> 108
            KeyEvent.KEYCODE_BACK -> return super.dispatchKeyEvent(event)
            else -> event.scanCode.takeIf { it > 0 } ?: fallbackEvdevKey(event.keyCode)
        }
        if (!inputEnabled || !attached || evdev <= 0) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) sendKey(evdev, true)
            KeyEvent.ACTION_UP -> sendKey(evdev, false)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    fun enableGameInput(enabled: Boolean) {
        if (!enabled && inputEnabled) releaseInput()
        inputEnabled = enabled
        mouse?.foreground(enabled && !mouseDisabled)
        if (enabled && attached) requestFocus()
    }

    fun setMouseDisabled(disabled: Boolean) {
        if (mouseDisabled == disabled) return
        mouseDisabled = disabled
        releasePointer()
        mouse?.foreground(inputEnabled && !disabled)
    }

    fun setDirectTouch(direct: Boolean) {
        val localMouse = mouse ?: return
        if (localMouse.usesRelativeTouch == !direct) return
        releasePointer()
        localMouse.setTouchpadEnabled(!direct)
    }

    private fun releaseInput() {
        mouse?.reset()
        releasePointer()
        for (key in pressedKeys) Compositor.nativeSendKey(key, 0)
        pressedKeys.clear()
    }

    private fun releasePointer() {
        if (pointerId == -1) return
        sendPointer(MotionEvent.ACTION_UP, pointerX, pointerY)
        pointerId = -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || !attached) return false
        if (mouseDisabled && (event.isFromSource(InputDevice.SOURCE_MOUSE) || mouse?.usesRelativeTouch == true)) return false
        if (mouse?.touch(event) == true) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                pointerId = event.getPointerId(0)
                pointerX = event.x
                pointerY = event.y
                mouse?.rememberDirectPosition(pointerX, pointerY)
                sendPointer(MotionEvent.ACTION_DOWN, pointerX, pointerY)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    pointerX = event.getX(index)
                    pointerY = event.getY(index)
                    mouse?.rememberDirectPosition(pointerX, pointerY)
                    sendPointer(MotionEvent.ACTION_MOVE, pointerX, pointerY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                if (event.getPointerId(index) == pointerId) {
                    pointerX = event.getX(index)
                    pointerY = event.getY(index)
                    mouse?.rememberDirectPosition(pointerX, pointerY)
                    releasePointer()
                }
            }
            MotionEvent.ACTION_CANCEL -> releasePointer()
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        (inputEnabled && !mouseDisabled && attached && mouse?.motion(event) == true) || super.onGenericMotionEvent(event)

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!inputEnabled || mouseDisabled || !attached) return super.onHoverEvent(event)
        if (mouse?.motion(event) == true) return true
        if (event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            sendPointer(MotionEvent.ACTION_MOVE, event.x, event.y)
        }
        return true
    }

    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? =
        if (inputEnabled && attached && mouse?.drawsPhysicalPointer == true &&
            event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)
        else super.onResolvePointerIcon(event, pointerIndex)

    companion object {
        private const val INPUT_WIDTH = 1920
        private const val INPUT_HEIGHT = 1080

        // The upstream compositor thread is process-wide and lives across Surface recreation.
        private var started = false
        private var owner: GamescopeHostView? = null
    }
}
