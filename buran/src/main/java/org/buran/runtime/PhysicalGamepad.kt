package org.buran.runtime

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PhysicalGamepad(context: Context, file: File) : InputManager.InputDeviceListener {
    init {
        System.loadLibrary("buran_gamepad")
    }

    private val manager = checkNotNull(context.getSystemService(InputManager::class.java))
    private val shared = RandomAccessFile(file, "rw").use {
        it.setLength(SHARED_SIZE)
        it.channel.map(FileChannel.MapMode.READ_WRITE, 0, SHARED_SIZE)
    }
    private val state = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
    private var deviceId: Int? = null
    private var keys = 0
    private var directions = 0
    private var active = false

    init {
        selectDevice()
    }

    fun listen() {
        manager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        selectDevice()
    }

    fun close() {
        manager.unregisterInputDeviceListener(this)
        active = false
        deviceId = null
        reset()
    }

    fun setActive(enabled: Boolean) {
        if (active == enabled) return
        active = enabled
        if (!enabled) reset()
    }

    override fun onInputDeviceAdded(id: Int) {
        if (deviceId == null) selectDevice()
    }

    override fun onInputDeviceRemoved(id: Int) {
        if (deviceId == id) selectDevice()
    }

    override fun onInputDeviceChanged(id: Int) {
        if (deviceId == null || deviceId == id) selectDevice()
    }

    private fun selectDevice() {
        val current = deviceId?.let(manager::getInputDevice)
        deviceId = if (current?.isGamepad() == true) current.id else
            manager.inputDeviceIds.firstOrNull { manager.getInputDevice(it)?.isGamepad() == true }
        reset()
    }

    fun key(event: KeyEvent): Boolean {
        if (!active || event.deviceId != deviceId ||
            !(event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD))
        ) return false
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        val button = buttonKeys.indexOf(event.keyCode)
        if (button >= 0) {
            val flag = 1 shl button
            keys = if (pressed) keys or flag else keys and flag.inv()
        } else {
            val device = event.device ?: return false
            val axis = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L2 -> 4
                KeyEvent.KEYCODE_BUTTON_R2 -> 5
                else -> return false
            }
            if (device.triggerRange(axis) == null) putAxis(axis, if (pressed) 1f else -1f)
        }
        publish()
        return true
    }

    fun motion(event: MotionEvent): Boolean {
        if (!active || event.deviceId != deviceId || event.actionMasked != MotionEvent.ACTION_MOVE ||
            !event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        ) return false
        val device = event.device ?: return false
        putAxis(0, event.stick(MotionEvent.AXIS_X))
        putAxis(1, event.stick(MotionEvent.AXIS_Y))
        putAxis(2, event.stick(MotionEvent.AXIS_Z, MotionEvent.AXIS_RX))
        putAxis(3, event.stick(MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY))
        for (axis in 4..5) {
            val range = device.triggerRange(axis) ?: continue
            val value = event.getAxisValue(range.axis)
            putAxis(axis, ((value - range.min) / range.range) * 2f - 1f)
        }
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        directions = (if (y < -0.5f) 1 shl 11 else if (y > 0.5f) 1 shl 12 else 0) or
            (if (x < -0.5f) 1 shl 13 else if (x > 0.5f) 1 shl 14 else 0)
        publish()
        return true
    }

    private fun reset() {
        keys = 0
        directions = 0
        for (axis in 0..3) putAxis(axis, 0f)
        for (axis in 4..5) putAxis(axis, -1f)
        publish()
    }

    private fun putAxis(index: Int, value: Float) {
        state.putShort(index * 2, (value.coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
    }

    private fun publish() {
        val buttons = keys or directions
        for (i in buttonKeys.indices) state.put(12 + i, ((buttons shr i) and 1).toByte())
        // SDL uses up/right/down/left bits 1/2/4/8 in the byte after the buttons.
        val verticalHat = when ((buttons shr 11) and 3) {
            1 -> 1
            2 -> 4
            else -> 0
        }
        val horizontalHat = when ((buttons shr 13) and 3) {
            1 -> 8
            2 -> 2
            else -> 0
        }
        state.put(27, (verticalHat or horizontalHat).toByte())
        writeState(shared, state.array(), deviceId != null)
    }

    private fun InputDevice.isGamepad() =
        !isVirtual && (supportsSource(InputDevice.SOURCE_GAMEPAD) || supportsSource(InputDevice.SOURCE_JOYSTICK))

    private fun InputDevice.triggerRange(axis: Int): InputDevice.MotionRange? {
        val (primary, fallback) = if (axis == 4) MotionEvent.AXIS_LTRIGGER to MotionEvent.AXIS_BRAKE else
            MotionEvent.AXIS_RTRIGGER to MotionEvent.AXIS_GAS
        return (getMotionRange(primary, InputDevice.SOURCE_JOYSTICK)
            ?: getMotionRange(fallback, InputDevice.SOURCE_JOYSTICK))?.takeIf { it.range > 0f }
    }

    private fun MotionEvent.stick(axis: Int, fallback: Int = axis): Float {
        val range = device?.let { it.getMotionRange(axis, source) ?: it.getMotionRange(fallback, source) }
            ?: return 0f
        val value = getAxisValue(range.axis)
        return if (abs(value) <= range.flat) 0f else value
    }

    private external fun writeState(shared: ByteBuffer, state: ByteArray, connected: Boolean)

    companion object {
        const val FILE_NAME = "buran-local-pad.mem"
        const val SHARED_SIZE = 64L

        fun gameplaySharedFile(context: Context): File? = context.buranFilesDir.resolve("gamepad_shm/gamepad.mem")
            .takeIf { it.isFile && it.length() == SHARED_SIZE }

        private val buttonKeys = intArrayOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
