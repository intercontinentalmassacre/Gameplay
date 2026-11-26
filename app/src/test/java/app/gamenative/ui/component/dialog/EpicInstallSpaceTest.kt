package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class EpicInstallSpaceTest {
    @Test
    fun countsPartialDownloadOnceWithoutDiscountingDlc() {
        assertEquals(68L, remainingEpicInstallBytes(117, 49, 100))
        assertEquals(17L, remainingEpicInstallBytes(117, 130, 100))
        assertEquals(117L, remainingEpicInstallBytes(117, 0, 100))
    }
}
