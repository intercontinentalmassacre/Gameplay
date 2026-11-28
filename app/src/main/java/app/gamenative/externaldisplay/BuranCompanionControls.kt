package app.gamenative.externaldisplay

import android.os.Binder
import android.os.Bundle
import android.os.ResultReceiver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import app.gamenative.PrefManager
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.loadPerformanceHudConfig
import app.gamenative.ui.data.persistPerformanceHudConfig
import com.winlator.widget.TouchpadView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

class BuranCompanionControls(
    private val receiver: ResultReceiver,
    val capabilities: Set<String>,
    mouseDisabled: Boolean,
    touchscreen: Boolean,
    outputFps: Boolean,
    fpsLimit: Int,
    val sessionId: String?,
    mangoHudEnabled: Boolean,
    val touchSensitivity: Float,
    framegenRequested: Boolean,
    framegenActive: Boolean,
    framegenFailed: Boolean,
) : TouchpadView.RelativeInput {
    private val lifetime = Binder()
    private var active = true
    var stopping by mutableStateOf(false)
        private set
    var mouseDisabled by mutableStateOf(mouseDisabled)
        private set
    var touchscreen by mutableStateOf(touchscreen)
        private set
    val supportsPerformanceHud = "performanceHud" in capabilities
    var hudOnLowerScreen by mutableStateOf(false)
        private set
    private var sampledFps = Float.NaN
    @Volatile private var sampledGeneratedFps: Int? = null
    var performanceHudEnabled by mutableStateOf(if (supportsPerformanceHud) PrefManager.showFps else outputFps)
        private set
    var performanceHudConfig by mutableStateOf(loadPerformanceHudConfig())
        private set
    var mangoHudAvailable by mutableStateOf("mangoHud" in capabilities)
        private set
    var mangoHudEnabled by mutableStateOf(mangoHudEnabled)
        private set
    val supportsFramegen = "framegen2x" in capabilities
    var framegenActive by mutableStateOf(framegenActive && !framegenFailed)
        private set
    var framegenPending by mutableStateOf(framegenRequested && !framegenActive && !framegenFailed)
        private set
    var framegenFailed by mutableStateOf(framegenFailed)
        private set
    private var framegenCommand: Boolean? = null
    var keyboardOpen by mutableStateOf(false)
    var fpsLimiterEnabled by mutableStateOf(fpsLimit in 15..120)
        private set
    var fpsTarget by mutableStateOf(fpsLimit.takeIf { it in 15..120 } ?: 60)
        private set

    override fun move(dx: Float, dy: Float) {
        if (!mouseDisabled) send(1, bundleOf("dx" to dx, "dy" to dy))
    }

    override fun button(button: Pointer.Button, down: Boolean) {
        if (mouseDisabled) return
        val code = when (button) {
            Pointer.Button.BUTTON_LEFT -> 0x110
            Pointer.Button.BUTTON_RIGHT -> 0x111
            Pointer.Button.BUTTON_MIDDLE -> 0x112
            else -> return
        }
        send(2, bundleOf("button" to code, "down" to down))
    }

    override fun scroll(steps: Int) {
        if (!mouseDisabled) send(3, bundleOf("steps" to steps))
    }

    fun stop() {
        if (!active || stopping) return
        reset()
        receiver.send(4, null)
        stopping = true
    }

    override fun reset() = send(5, Bundle().apply { putBinder("lifetime", lifetime) })

    fun key(key: XKeycode, down: Boolean) {
        val evdev = (key.id.toInt() and 0xff) - 8
        if (evdev > 0) send(6, bundleOf("key" to evdev, "down" to down))
    }

    fun toggleMouse() {
        reset()
        mouseDisabled = !mouseDisabled
        send(7, bundleOf("disabled" to mouseDisabled))
    }

    fun toggleTouchscreen() {
        touchscreen = !touchscreen
        send(8, bundleOf("direct" to touchscreen))
    }

    fun restart() {
        if (!active || stopping) return
        reset()
        receiver.send(9, null)
        stopping = true
    }

    fun togglePerformanceHud() {
        performanceHudEnabled = !performanceHudEnabled
        if (supportsPerformanceHud) {
            PrefManager.showFps = performanceHudEnabled
            syncPerformanceHud()
        } else {
            send(10, bundleOf("enabled" to performanceHudEnabled))
        }
    }

    fun useLowerScreenHud(lower: Boolean) {
        if (hudOnLowerScreen == lower) return
        hudOnLowerScreen = lower
        sampledFps = Float.NaN
        sampledGeneratedFps = null
        if (lower && mangoHudEnabled) send(13, bundleOf("enabled" to false))
        if (!supportsPerformanceHud && performanceHudEnabled) {
            send(10, bundleOf("enabled" to !lower))
        }
        syncPerformanceHud()
    }

    fun onHudVisibilityChanged(enabled: Boolean) {
        performanceHudEnabled = enabled
        if (supportsPerformanceHud) PrefManager.showFps = enabled
        if (hudOnLowerScreen) syncPerformanceHud()
    }

    fun onOutputFps(fps: Float, generatedFps: Int?) {
        sampledFps = if (hudOnLowerScreen && fps.isFinite() && fps >= 0f) fps else Float.NaN
        sampledGeneratedFps = generatedFps.takeIf { hudOnLowerScreen && framegenActive }
    }

    fun frameRateForHud() = sampledFps
    fun generatedFrameRateForHud() = sampledGeneratedFps

    fun changePerformanceHudConfig(config: PerformanceHudConfig) {
        performanceHudConfig = config
        persistPerformanceHudConfig(config)
        syncPerformanceHud()
    }

    fun syncPerformanceHud() {
        if (!supportsPerformanceHud) return
        val config = performanceHudConfig
        send(12, Bundle().apply {
            putBoolean("enabled", performanceHudEnabled && !hudOnLowerScreen)
            putBoolean("dualScreen", hudOnLowerScreen)
            putBoolean(
                "reportOutputFps",
                hudOnLowerScreen && performanceHudEnabled && (config.showFrameRate || config.showFrameRateGraph),
            )
            putBoolean("showFrameRate", config.showFrameRate)
            putBoolean("showCpuUsage", config.showCpuUsage)
            putBoolean("showGpuUsage", config.showGpuUsage)
            putBoolean("showRamUsage", config.showRamUsage)
            putBoolean("showBatteryLevel", config.showBatteryLevel)
            putBoolean("lowBatteryWarningEnabled", config.lowBatteryWarningEnabled)
            putInt("lowBatteryThresholdPercent", config.lowBatteryThresholdPercent)
            putBoolean("showPowerDraw", config.showPowerDraw)
            putBoolean("showBatteryRuntime", config.showBatteryRuntime)
            putBoolean("showBatteryTemperature", config.showBatteryTemperature)
            putBoolean("highBatteryTemperatureWarningEnabled", config.highBatteryTemperatureWarningEnabled)
            putInt("highBatteryTemperatureThresholdC", config.highBatteryTemperatureThresholdC)
            putBoolean("showClockTime", config.showClockTime)
            putBoolean("showCpuTemperature", config.showCpuTemperature)
            putBoolean("showGpuTemperature", config.showGpuTemperature)
            putBoolean("showFrameRateGraph", config.showFrameRateGraph)
            putBoolean("showCpuUsageGraph", config.showCpuUsageGraph)
            putBoolean("showGpuUsageGraph", config.showGpuUsageGraph)
            putFloat("backgroundOpacity", config.backgroundOpacity)
            putFloat("colorIntensity", config.colorIntensity)
            putBoolean("showTextOutline", config.showTextOutline)
            putString("size", config.size.prefValue)
            putBoolean("compactMode", false)
        })
    }

    fun updateMangoHud(available: Boolean, enabled: Boolean) {
        mangoHudAvailable = available
        mangoHudEnabled = available && enabled
    }

    fun toggleMangoHud() {
        if (!mangoHudAvailable || hudOnLowerScreen) return
        send(13, bundleOf("enabled" to !mangoHudEnabled))
    }

    fun toggleFramegen() {
        if (!active || stopping || !supportsFramegen || framegenPending || framegenFailed) return
        val requested = !framegenActive
        framegenCommand = requested
        framegenPending = true
        send(14, bundleOf("enabled" to requested))
    }

    fun updateFramegen(requested: Boolean, enabled: Boolean, failed: Boolean): Boolean? {
        if (!active || !supportsFramegen) return null
        framegenFailed = failed
        framegenActive = enabled && !failed
        if (!framegenActive) sampledGeneratedFps = null
        framegenPending = !failed && (requested != enabled || framegenCommand?.let { it != requested } == true)
        val accepted = framegenCommand?.takeIf { !framegenPending && !failed && it == requested }
        if (!framegenPending) framegenCommand = null
        return accepted
    }

    fun setFpsLimiter(enabled: Boolean) {
        fpsLimiterEnabled = enabled
        send(11, bundleOf("fps" to if (enabled) fpsTarget else 0))
    }

    fun changeFpsTarget(fps: Int) {
        fpsTarget = fps.coerceIn(15, 120)
        if (fpsLimiterEnabled) send(11, bundleOf("fps" to fpsTarget))
    }

    fun close() {
        reset()
        active = false
    }

    private fun send(code: Int, data: Bundle) {
        if (active && !stopping) receiver.send(code, data)
    }
}
