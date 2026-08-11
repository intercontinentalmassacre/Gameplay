package app.gamenative.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceHudGpuUsageTest {

    @Test
    fun `kgsl usage is calculated from cumulative counter deltas`() {
        assertNull(kgslGpuUsagePercent(null, null, busy = 1_000L, total = 2_000L))
        assertEquals(
            60,
            kgslGpuUsagePercent(
                previousBusy = 1_000L,
                previousTotal = 2_000L,
                busy = 1_300L,
                total = 2_500L,
            ),
        )
    }

    @Test
    fun `counter reset does not report a false gpu load`() {
        assertNull(
            kgslGpuUsagePercent(
                previousBusy = 1_000L,
                previousTotal = 2_000L,
                busy = 10L,
                total = 20L,
            ),
        )
    }

    @Test
    fun `clock residency is converted to gpu activity when busy counter is unavailable`() {
        assertNull(kgslClockResidencyUsagePercent(null, null, residencyMs = 1_000L, wallMs = 10_000L))
        assertEquals(
            75,
            kgslClockResidencyUsagePercent(
                previousResidencyMs = 1_000L,
                previousWallMs = 10_000L,
                residencyMs = 1_750L,
                wallMs = 11_000L,
            ),
        )
    }
}
