package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Test

class DragonsDogma2LaunchFixTest {
    @Test
    fun appendsDefaultForDragonsDogma2AndKeepsOtherArgs() {
        val args = "-name=\"Test /WineDetectionEnabled:True\" -windowed"
        val fixed = withDragonsDogma2LaunchFix(2054970, args)
        assertEquals("$args /WineDetectionEnabled:False", fixed)
        assertEquals(fixed, withDragonsDogma2LaunchFix(2054970, fixed))
        assertEquals("/WineDetectionEnabled:False", withDragonsDogma2LaunchFix(2054970, ""))
    }

    @Test
    fun preservesExplicitOptionAndOtherGames() {
        val args = "-windowed \"/winedetectionenabled:True\""
        assertEquals(args, withDragonsDogma2LaunchFix(2054970, args))
        assertEquals("-windowed", withDragonsDogma2LaunchFix(200260, "-windowed"))
    }
}
