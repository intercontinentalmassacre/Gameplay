package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Steam40800FixTest {

    @Test
    fun `withWinComponent replaces existing key and appends missing one`() {
        assertEquals(
            "direct3d=1,xaudio2_7=1",
            withWinComponent("direct3d=1,xaudio2_7=0", "xaudio2_7", "1"),
        )
        assertEquals(
            "direct3d=1,xaudio2_7=1",
            withWinComponent("direct3d=1", "xaudio2_7", "1"),
        )
    }

    @Test
    fun `super meat boy fix is registered`() {
        assertNotNull(STEAM_Fix_40800)
        assertEquals("40800", STEAM_Fix_40800.gameId)
    }
}
