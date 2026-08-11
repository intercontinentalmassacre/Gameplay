package app.gamenative.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerConnectionStateTest {
    @Test
    fun `device changes increment generation for focus recovery`() {
        val initial = ControllerConnectionState(connected = false)
        val connected = controllerConnectionChanged(initial, connected = true)
        val disconnected = controllerConnectionChanged(connected, connected = false)

        assertEquals(true, connected.connected)
        assertEquals(1, connected.generation)
        assertEquals(false, disconnected.connected)
        assertEquals(2, disconnected.generation)
    }
}
