// Adapted for Buran from Gameplay, app/src/main/java/app/gamenative/ui/data/PerformanceHudConfig.kt.
// https://github.com/intercontinentalmassacre/Gameplay/blob/08386c0/app/src/main/java/app/gamenative/ui/data/PerformanceHudConfig.kt
// Distributed under the GNU General Public License version 3.
// See assets/notices/gameplay-performance-hud-gpl-3.0.txt.

package org.buran.runtime.hud

import android.os.Bundle


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

        internal val FPS_ONLY = PerformanceHudConfig(
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
        )

        internal fun fromCompanion(bundle: Bundle): PerformanceHudConfig {
            val defaults = PerformanceHudConfig()
            fun flag(name: String, fallback: Boolean) = bundle.getBoolean(name, fallback)
            fun setting(name: String, fallback: Float) =
                bundle.getFloat(name, fallback).takeIf(Float::isFinite) ?: fallback
            return PerformanceHudConfig(
                showFrameRate = flag("showFrameRate", defaults.showFrameRate),
                showCpuUsage = flag("showCpuUsage", defaults.showCpuUsage),
                showGpuUsage = flag("showGpuUsage", defaults.showGpuUsage),
                showRamUsage = flag("showRamUsage", defaults.showRamUsage),
                showBatteryLevel = flag("showBatteryLevel", defaults.showBatteryLevel),
                lowBatteryWarningEnabled = flag("lowBatteryWarningEnabled", defaults.lowBatteryWarningEnabled),
                lowBatteryThresholdPercent = bundle.getInt("lowBatteryThresholdPercent", defaults.lowBatteryThresholdPercent)
                    .coerceIn(0, 100),
                showPowerDraw = flag("showPowerDraw", defaults.showPowerDraw),
                showBatteryRuntime = flag("showBatteryRuntime", defaults.showBatteryRuntime),
                showBatteryTemperature = flag("showBatteryTemperature", defaults.showBatteryTemperature),
                highBatteryTemperatureWarningEnabled = flag(
                    "highBatteryTemperatureWarningEnabled", defaults.highBatteryTemperatureWarningEnabled,
                ),
                highBatteryTemperatureThresholdC = bundle.getInt(
                    "highBatteryTemperatureThresholdC", defaults.highBatteryTemperatureThresholdC,
                ).coerceIn(0, 100),
                showClockTime = flag("showClockTime", defaults.showClockTime),
                showCpuTemperature = flag("showCpuTemperature", defaults.showCpuTemperature),
                showGpuTemperature = flag("showGpuTemperature", defaults.showGpuTemperature),
                showFrameRateGraph = flag("showFrameRateGraph", defaults.showFrameRateGraph),
                showCpuUsageGraph = flag("showCpuUsageGraph", defaults.showCpuUsageGraph),
                showGpuUsageGraph = flag("showGpuUsageGraph", defaults.showGpuUsageGraph),
                backgroundOpacity = setting("backgroundOpacity", defaults.backgroundOpacity),
                colorIntensity = setting("colorIntensity", defaults.colorIntensity),
                showTextOutline = flag("showTextOutline", defaults.showTextOutline),
                size = PerformanceHudSize.fromPrefValue(bundle.getString("size")),
            )
        }
    }
}
