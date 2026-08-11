package app.gamenative.ui.component.dialog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolutionUtilsTest {
    @Test
    fun evenRoundProducesEvenDimensions() {
        assertEquals(1280, evenRound(1280.4f))
        assertEquals(1282, evenRound(1281f))
        assertEquals(720, evenRound(719.1f))
    }

    @Test
    fun aspectRatioSupportsCommonMobileDisplays() {
        assertEquals("16:9", calculateAspectRatio(1920, 1080))
        assertEquals("4:3", calculateAspectRatio(1280, 960))
        assertEquals("19.5:9", calculateAspectRatio(2340, 1080))
        assertEquals("21.5:9", calculateAspectRatio(2580, 1080))
        assertEquals("20:9", calculateAspectRatio(2400, 1080))
    }

    @Test
    fun aspectRatioRejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException::class.java) { calculateAspectRatio(0, 1080) }
    }
}
