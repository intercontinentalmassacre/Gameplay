package app.gamenative.utils

import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.steamcontroller.*
import app.gamenative.steamcontroller.TritonProtocol as Buttons
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.xserver.Pointer

/** Import the base action set into the physical gamepad editor's single-binding-per-input model. */
internal object CommunityGamepadBindings {
    private val keyCodes = mapOf(
        Buttons.BTN_A to KeyEvent.KEYCODE_BUTTON_A, Buttons.BTN_B to KeyEvent.KEYCODE_BUTTON_B,
        Buttons.BTN_X to KeyEvent.KEYCODE_BUTTON_X, Buttons.BTN_Y to KeyEvent.KEYCODE_BUTTON_Y,
        Buttons.BTN_LBUMPER to KeyEvent.KEYCODE_BUTTON_L1, Buttons.BTN_RBUMPER to KeyEvent.KEYCODE_BUTTON_R1,
        Buttons.BTN_VIEW to KeyEvent.KEYCODE_BUTTON_START, Buttons.BTN_MENU to KeyEvent.KEYCODE_BUTTON_SELECT,
        Buttons.BTN_L3 to KeyEvent.KEYCODE_BUTTON_THUMBL, Buttons.BTN_R3 to KeyEvent.KEYCODE_BUTTON_THUMBR,
        Buttons.BTN_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT, Buttons.BTN_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        Buttons.BTN_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP, Buttons.BTN_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        Buttons.BTN_LTRIG_CLICK to KeyEvent.KEYCODE_BUTTON_L2, Buttons.BTN_RTRIG_CLICK to KeyEvent.KEYCODE_BUTTON_R2,
    )
    private val padButtons = listOf(Binding.GAMEPAD_BUTTON_A, Binding.GAMEPAD_BUTTON_B, Binding.GAMEPAD_BUTTON_X,
        Binding.GAMEPAD_BUTTON_Y, Binding.GAMEPAD_BUTTON_L1, Binding.GAMEPAD_BUTTON_R1, Binding.GAMEPAD_BUTTON_SELECT,
        Binding.GAMEPAD_BUTTON_START, Binding.GAMEPAD_BUTTON_L3, Binding.GAMEPAD_BUTTON_R3, Binding.GAMEPAD_BUTTON_L2, Binding.GAMEPAD_BUTTON_R2)
    private val dpad = listOf(Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_RIGHT, Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT)

    private fun ScOutput.toBinding(): Binding? = when (this) {
        is ScOutput.Key -> keys.singleOrNull()?.let { key -> Binding.entries.firstOrNull { it.keycode == key && it.name.startsWith("KEY_") } }
        is ScOutput.GamepadButton -> padButtons.getOrNull(idx)
        is ScOutput.GamepadDpad -> dpad.getOrNull(index)
        is ScOutput.MouseButton -> when (button) {
            Pointer.Button.BUTTON_LEFT -> Binding.MOUSE_LEFT_BUTTON
            Pointer.Button.BUTTON_RIGHT -> Binding.MOUSE_RIGHT_BUTTON
            Pointer.Button.BUTTON_MIDDLE -> Binding.MOUSE_MIDDLE_BUTTON
            Pointer.Button.BUTTON_SCROLL_UP -> Binding.MOUSE_SCROLL_UP
            Pointer.Button.BUTTON_SCROLL_DOWN -> Binding.MOUSE_SCROLL_DOWN
            else -> null
        }
        else -> null
    }

    fun read(vdf: String): Map<Int, Binding> = buildMap {
        val profile = SteamControllerProfileImporter.import(vdf)
        for ((bit, mapping) in profile.buttons) {
            val code = keyCodes[bit] ?: continue
            if (mapping.activator == Activator.Regular && !mapping.toggle) mapping.output.toBinding()?.let { put(code, it) }
        }
        fun stick(mode: StickMode, x: Int, y: Int) {
            val directions = when (mode) {
                is StickMode.DPad -> listOf(mode.left, mode.right, mode.up, mode.down).map { it.toBinding() }
                is StickMode.Mouse -> listOf(Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT, Binding.MOUSE_MOVE_UP, Binding.MOUSE_MOVE_DOWN)
                is StickMode.JoystickMove -> if (mode.stick == Stick.LEFT)
                    listOf(Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT, Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN)
                else listOf(Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT, Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN)
                else -> return
            }
            val axes = listOf(x, x, y, y)
            directions.forEachIndexed { i, binding ->
                if (binding != null) put(ExternalControllerBinding.getKeyCodeForAxis(axes[i], if (i % 2 == 0) -1 else 1), binding)
            }
        }
        stick(profile.leftStick, MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        stick(profile.rightStick, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ)
        fun trigger(mode: TriggerMode, code: Int) {
            if (mode is TriggerMode.Staged) (mode.full.toBinding() ?: mode.soft.toBinding())?.let { put(code, it) }
        }
        trigger(profile.leftTrigger, KeyEvent.KEYCODE_BUTTON_L2)
        trigger(profile.rightTrigger, KeyEvent.KEYCODE_BUTTON_R2)
        // A missing/motion-only layout must never replace the active profile with the importer's default triggers.
        if (isNotEmpty()) {
            if (profile.leftTrigger is TriggerMode.Axis) putIfAbsent(KeyEvent.KEYCODE_BUTTON_L2, Binding.GAMEPAD_BUTTON_L2)
            if (profile.rightTrigger is TriggerMode.Axis) putIfAbsent(KeyEvent.KEYCODE_BUTTON_R2, Binding.GAMEPAD_BUTTON_R2)
            put(KeyEvent.KEYCODE_BUTTON_MODE, Binding.OPEN_NAVIGATION_MENU)
        }
    }
}
