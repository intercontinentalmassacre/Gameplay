// Adapted for Buran from Gameplay, app/src/main/java/app/gamenative/ui/widget/ThermalThrottle.kt.
// https://github.com/intercontinentalmassacre/Gameplay/blob/08386c0/app/src/main/java/app/gamenative/ui/widget/ThermalThrottle.kt
// Distributed under the GNU General Public License version 3.
// See assets/notices/gameplay-performance-hud-gpl-3.0.txt.

package org.buran.runtime.hud

import java.io.File

/** Pick Android's status once it has shown a signal, otherwise read the kernel's cooling devices. */
internal class ThermalThrottle(
    private val platformStatus: () -> Int,
    private val platformHeadroom: () -> Float,
    private val kernelStatus: () -> Int?,
) {
    private var platformReports = false
    private var nextHeadroomReadMs = 0L

    @Synchronized
    fun read(nowMs: Long): Int? {
        val status = platformStatus()
        if (status > 0) platformReports = true
        if (!platformReports && nowMs >= nextHeadroomReadMs) {
            // PowerManager may return NaN when headroom is sampled too often.
            nextHeadroomReadMs = nowMs + 10_000L
            platformReports = platformHeadroom().isFinite()
        }
        return if (platformReports) status else kernelStatus()
    }
}

/** Maps CPU/GPU cooling states to Android-style severity levels. A cooling state is a table index. */
internal class KernelThermal(root: File = File("/sys/class/thermal")) {
    private data class Device(val stateFile: File, val maxState: Int, val coreOffline: Boolean)

    private val devices by lazy {
        root.listFiles { file -> file.name.startsWith("cooling_device") }.orEmpty().mapNotNull { dir ->
            val type = read(File(dir, "type"))?.lowercase() ?: return@mapNotNull null
            val coreOffline = type.startsWith("thermal-pause") || type.startsWith("pause-cpu") ||
                type.startsWith("cpu-hotplug") || type.startsWith("cpu-isolate")
            val clockCap = type.startsWith("cpufreq") || type.startsWith("cpu-cluster") ||
                type.startsWith("thermal-cluster") || type == "gpu" || type.contains("kgsl") ||
                (type.startsWith("devfreq") && type.contains("gpu"))
            if (!coreOffline && !clockCap) return@mapNotNull null
            val maxState = read(File(dir, "max_state"))?.toIntOrNull()?.takeIf { it > 0 }
                ?: return@mapNotNull null
            Device(File(dir, "cur_state"), maxState, coreOffline)
        }
    }

    fun readStatus(): Int? {
        var severity: Int? = null
        for (device in devices) {
            val state = read(device.stateFile)?.toIntOrNull()?.takeIf { it >= 0 } ?: continue
            val fraction = state.toDouble() / device.maxState
            val current = when {
                device.coreOffline && state > 0 || fraction >= 0.5 -> 3
                fraction >= 0.2 -> 2
                state > 0 -> 1
                else -> 0
            }
            severity = maxOf(severity ?: 0, current)
            if (severity == 3) break
        }
        return severity
    }

    private fun read(file: File): String? =
        runCatching { file.bufferedReader().use { it.readLine()?.trim() } }.getOrNull()
}
