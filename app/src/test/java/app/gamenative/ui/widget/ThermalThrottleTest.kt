package app.gamenative.ui.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThermalThrottleTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun coolingStatesMatchThorDeviceTypesAndIgnoreBacklight() {
        val cpu = coolingDevice(0, "cpufreq-cpu3", 20, 0)
        val gpu = coolingDevice(1, "devfreq-3d00000.qcom,kgsl-3d0", 8, 0)
        val pause = coolingDevice(2, "thermal-pause-C0", 1, 0)
        coolingDevice(3, "panel0-backlight", 255, 255)
        coolingDevice(4, "display-fps", 16, 16)
        val reader = KernelThermal(temp.root)

        assertEquals(0, reader.readStatus())
        cpu.writeText("1")
        assertEquals(1, reader.readStatus())
        cpu.writeText("4")
        assertEquals(2, reader.readStatus())
        gpu.writeText("4")
        assertEquals(3, reader.readStatus())
        gpu.writeText("0")
        pause.writeText("1")
        assertEquals(3, reader.readStatus())
    }

    @Test
    fun missingAndInvalidReadingsAreUnknown() {
        val state = coolingDevice(0, "gpu", 8, -1)
        coolingDevice(1, "panel1-backlight", 255, 255)
        val reader = KernelThermal(temp.root)

        assertNull(reader.readStatus())
        state.writeText("0")
        assertEquals(0, reader.readStatus())
        state.delete()
        assertNull(reader.readStatus())
    }

    @Test
    fun positivePlatformStatusTakesOverForTheRestOfTheSession() {
        var platformStatus = 0
        val reader = ThermalThrottle({ platformStatus }, { Float.NaN }, { 2 })
        assertEquals(2, reader.read(0))
        platformStatus = 1
        assertEquals(1, reader.read(1))
        platformStatus = 0
        assertEquals(0, reader.read(2))
    }

    @Test
    fun finiteHeadroomTakesOverAndSamplingIsRateLimited() {
        var reads = 0
        val reader = ThermalThrottle(
            platformStatus = { 0 },
            platformHeadroom = { if (++reads == 1) Float.NaN else 0.25f },
            kernelStatus = { 3 },
        )
        assertEquals(3, reader.read(0))
        assertEquals(3, reader.read(9_999))
        assertEquals(0, reader.read(10_000))
        assertEquals(0, reader.read(30_000))
        assertEquals(2, reads)
    }

    private fun coolingDevice(index: Int, type: String, max: Int, state: Int): File {
        val dir = temp.newFolder("cooling_device$index")
        dir.resolve("type").writeText(type)
        dir.resolve("max_state").writeText(max.toString())
        return dir.resolve("cur_state").apply { writeText(state.toString()) }
    }
}
