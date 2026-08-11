package app.gamenative.ui.util

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Controller family used to pick hint glyphs. */
enum class ControllerFamily {
    XBOX,
    PLAYSTATION,
}

data class ControllerConnectionState(
    val connected: Boolean,
    val generation: Int = 0,
)

private const val VENDOR_MICROSOFT = 0x045E
private const val VENDOR_SONY = 0x054C

private val playStationNameMarkers = listOf(
    "dualsense",
    "dualshock",
    "playstation",
    "ps5",
    "ps4",
    "ps3",
    "wireless controller",
    "sony interactive",
)

/**
 * Detects the family of the first connected game controller.
 * Defaults to [ControllerFamily.XBOX] when no controller is present or the
 * controller cannot be classified (generic/XInput devices included).
 */
fun detectControllerFamily(): ControllerFamily {
    var sawGamepad = false
    for (id in InputDevice.getDeviceIds()) {
        val device = InputDevice.getDevice(id) ?: continue
        if (device.isVirtual) continue
        val sources = device.sources
        val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!isGamepad) continue
        sawGamepad = true

        if (device.vendorId == VENDOR_SONY) return ControllerFamily.PLAYSTATION
        if (device.vendorId == VENDOR_MICROSOFT) continue

        val name = device.name?.lowercase().orEmpty()
        if ("xbox" in name) continue
        if (playStationNameMarkers.any { it in name }) return ControllerFamily.PLAYSTATION
    }
    return ControllerFamily.XBOX
}

fun hasConnectedController(): Boolean = InputDevice.getDeviceIds().any { id ->
    val device = InputDevice.getDevice(id) ?: return@any false
    if (device.isVirtual) return@any false
    val sources = device.sources
    sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
}

fun controllerConnectionChanged(
    previous: ControllerConnectionState,
    connected: Boolean,
): ControllerConnectionState = previous.copy(
    connected = connected,
    generation = previous.generation + 1,
)

/** Observes controller hot-plug and exposes generation for focus restoration effects. */
@Composable
fun rememberControllerConnectionState(): ControllerConnectionState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ControllerConnectionState(hasConnectedController())) }

    DisposableEffect(context) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return@DisposableEffect onDispose {}
        val listener = object : InputManager.InputDeviceListener {
            private fun update() {
                state = controllerConnectionChanged(state, hasConnectedController())
            }

            override fun onInputDeviceAdded(deviceId: Int) = update()
            override fun onInputDeviceRemoved(deviceId: Int) = update()
            override fun onInputDeviceChanged(deviceId: Int) = update()
        }
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { inputManager.unregisterInputDeviceListener(listener) }
    }

    return state
}

/** Observes controller (dis)connections and reports the current [ControllerFamily]. */
@Composable
fun rememberControllerFamily(): ControllerFamily {
    val context = LocalContext.current
    var family by remember { mutableStateOf(detectControllerFamily()) }

    DisposableEffect(context) {
        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return@DisposableEffect onDispose {}
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                family = detectControllerFamily()
            }

            override fun onInputDeviceRemoved(deviceId: Int) {
                family = detectControllerFamily()
            }

            override fun onInputDeviceChanged(deviceId: Int) {
                family = detectControllerFamily()
            }
        }
        inputManager.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose {
            inputManager.unregisterInputDeviceListener(listener)
        }
    }

    return family
}
