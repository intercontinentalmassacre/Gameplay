package app.gamenative.ui.data

import app.gamenative.PrefManager

/**
 * Size presets for the floating performance HUD.
 */
enum class PerformanceHudSize(val prefValue: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large"),
    ;

    companion object {
        fun fromPrefValue(value: String?): PerformanceHudSize {
            return values().firstOrNull { it.prefValue == value } ?: MEDIUM
        }
    }
}

/**
 * Controls which metrics are rendered inside the floating performance HUD.
 */
data class PerformanceHudConfig(
    val showFrameRate: Boolean = true,
    val showCpuUsage: Boolean = true,
    val showGpuUsage: Boolean = true,
    val showRamUsage: Boolean = true,
    val showBatteryLevel: Boolean = true,
    val lowBatteryWarningEnabled: Boolean = false,
    val lowBatteryThresholdPercent: Int = 10,
    val showPowerDraw: Boolean = true,
    val showBatteryRuntime: Boolean = false,
    val showBatteryTemperature: Boolean = false,
    val highBatteryTemperatureWarningEnabled: Boolean = false,
    val highBatteryTemperatureThresholdC: Int = 40,
    val showClockTime: Boolean = false,
    val showCpuTemperature: Boolean = true,
    val showGpuTemperature: Boolean = true,
    val showFrameRateGraph: Boolean = false,
    val showCpuUsageGraph: Boolean = false,
    val showGpuUsageGraph: Boolean = false,
    val backgroundOpacity: Float = DEFAULT_BACKGROUND_OPACITY,
    val colorIntensity: Float = DEFAULT_COLOR_INTENSITY,
    val showTextOutline: Boolean = DEFAULT_SHOW_TEXT_OUTLINE,
    val size: PerformanceHudSize = PerformanceHudSize.MEDIUM,
) {
    companion object {
        const val DEFAULT_BACKGROUND_OPACITY = 0.72f
        const val DEFAULT_COLOR_INTENSITY = 1f
        const val DEFAULT_SHOW_TEXT_OUTLINE = true
    }
}

internal fun loadPerformanceHudConfig(): PerformanceHudConfig {
    return PerformanceHudConfig(
        showFrameRate = PrefManager.performanceHudShowFrameRate,
        showCpuUsage = PrefManager.performanceHudShowCpuUsage,
        showGpuUsage = PrefManager.performanceHudShowGpuUsage,
        showRamUsage = PrefManager.performanceHudShowRamUsage,
        showBatteryLevel = PrefManager.performanceHudShowBatteryLevel,
        lowBatteryWarningEnabled = PrefManager.performanceHudLowBatteryWarning,
        lowBatteryThresholdPercent = PrefManager.performanceHudLowBatteryThreshold,
        showPowerDraw = PrefManager.performanceHudShowPowerDraw,
        showBatteryRuntime = PrefManager.performanceHudShowBatteryRuntime,
        showBatteryTemperature = PrefManager.performanceHudShowBatteryTemperature,
        highBatteryTemperatureWarningEnabled = PrefManager.performanceHudHighBatteryTemperatureWarning,
        highBatteryTemperatureThresholdC = PrefManager.performanceHudHighBatteryTemperatureThreshold,
        showClockTime = PrefManager.performanceHudShowClockTime,
        showCpuTemperature = PrefManager.performanceHudShowCpuTemperature,
        showGpuTemperature = PrefManager.performanceHudShowGpuTemperature,
        showFrameRateGraph = PrefManager.performanceHudShowFrameRateGraph,
        showCpuUsageGraph = PrefManager.performanceHudShowCpuUsageGraph,
        showGpuUsageGraph = PrefManager.performanceHudShowGpuUsageGraph,
        backgroundOpacity = PrefManager.performanceHudBackgroundOpacity,
        colorIntensity = PrefManager.performanceHudColorIntensity,
        showTextOutline = PrefManager.performanceHudShowTextOutline,
        size = PerformanceHudSize.fromPrefValue(PrefManager.performanceHudSize),
    )
}

internal fun persistPerformanceHudConfig(config: PerformanceHudConfig) {
    PrefManager.performanceHudShowFrameRate = config.showFrameRate
    PrefManager.performanceHudShowCpuUsage = config.showCpuUsage
    PrefManager.performanceHudShowGpuUsage = config.showGpuUsage
    PrefManager.performanceHudShowRamUsage = config.showRamUsage
    PrefManager.performanceHudShowBatteryLevel = config.showBatteryLevel
    PrefManager.performanceHudLowBatteryWarning = config.lowBatteryWarningEnabled
    PrefManager.performanceHudLowBatteryThreshold = config.lowBatteryThresholdPercent
    PrefManager.performanceHudShowPowerDraw = config.showPowerDraw
    PrefManager.performanceHudShowBatteryRuntime = config.showBatteryRuntime
    PrefManager.performanceHudShowBatteryTemperature = config.showBatteryTemperature
    PrefManager.performanceHudHighBatteryTemperatureWarning = config.highBatteryTemperatureWarningEnabled
    PrefManager.performanceHudHighBatteryTemperatureThreshold = config.highBatteryTemperatureThresholdC
    PrefManager.performanceHudShowClockTime = config.showClockTime
    PrefManager.performanceHudShowCpuTemperature = config.showCpuTemperature
    PrefManager.performanceHudShowGpuTemperature = config.showGpuTemperature
    PrefManager.performanceHudShowFrameRateGraph = config.showFrameRateGraph
    PrefManager.performanceHudShowCpuUsageGraph = config.showCpuUsageGraph
    PrefManager.performanceHudShowGpuUsageGraph = config.showGpuUsageGraph
    PrefManager.performanceHudBackgroundOpacity = config.backgroundOpacity
    PrefManager.performanceHudColorIntensity = config.colorIntensity
    PrefManager.performanceHudShowTextOutline = config.showTextOutline
    PrefManager.performanceHudSize = config.size.prefValue
}
