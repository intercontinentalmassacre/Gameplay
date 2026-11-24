package app.gamenative.ui.component.dialog

import com.winlator.xserver.ScreenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolutionUtilsTest {
    @Test
    fun customResolutionKeepsPortraitAndSquareDimensions() {
        val portrait = requireNotNull(normalizeCustomResolution(719, 1279))
        assertEquals(720 to 1280, portrait)
        assertEquals("720x1280", ScreenInfo("${portrait.first}x${portrait.second}").toString())
        assertEquals(800 to 800, normalizeCustomResolution(800, 800))
        assertEquals(1280 to 720, normalizeCustomResolution(1279, 719))
    }

    @Test
    fun customResolutionRejectsDimensionsThatCannotFitInScreenInfo() {
        assertEquals(32766 to 720, normalizeCustomResolution(32765, 720))
        assertNull(normalizeCustomResolution(720, 32767))
        assertNull(normalizeCustomResolution(0, 720))
        assertNull(normalizeCustomResolution(null, 720))
    }

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
