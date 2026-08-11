package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualScreenDeviceTest {

    @Test
    fun `ayn thor is detected across build props variants`() {
        assertTrue(DualScreenDevice.isKnownDualScreenModel("AYN", "Thor", "thor"))
        assertTrue(DualScreenDevice.isKnownDualScreenModel("AYN", "AYN Thor", "ayn_thor"))
        assertTrue(DualScreenDevice.isKnownDualScreenModel("ayn", "THOR", "THOR"))
    }

    @Test
    fun `other devices are not flagged`() {
        assertFalse(DualScreenDevice.isKnownDualScreenModel("HONOR", "PTP-N49", "PTP-N49"))
        assertFalse(DualScreenDevice.isKnownDualScreenModel("AYN", "Odin 2", "odin2"))
        assertFalse(DualScreenDevice.isKnownDualScreenModel("samsung", "SM-S918B", "dm3q"))
        assertFalse(DualScreenDevice.isKnownDualScreenModel("Google", "Pixel 8", "shiba"))
    }
}
