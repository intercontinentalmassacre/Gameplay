package app.gamenative.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamescopeFrameStartTest {
    @Test
    fun discardsDesktopPublishesAndBackfillsGameAtOriginalTimes() {
        val tracker = GamescopeFrameStart()
        val counted = mutableListOf<Long>()
        val desktop = (1L..120L).map { it * 1_000 }
        val game = (0L..59L).map { 121_000 + it * 33 }

        (desktop + game.dropLast(1)).forEach { time ->
            assertFalse(tracker.onFrame(time) { counted.add(it) })
        }
        assertEquals(emptyList<Long>(), counted)
        assertFalse(tracker.gameContentSeen)

        assertTrue(tracker.onFrame(game.last()) { counted.add(it) })
        assertEquals(game, counted)
        assertTrue(tracker.gameContentSeen)

        val laterFrame = 130_000L
        assertFalse(tracker.onFrame(laterFrame) { counted.add(it) })
        assertEquals(game + laterFrame, counted)
    }

    @Test
    fun retainsFullStreakAndStartsNewSurfaceWithNoFrames() {
        val counted = mutableListOf<Long>()
        val tracker = GamescopeFrameStart()
        val game = (1L..60L).map { it * 250 }

        game.forEachIndexed { index, time ->
            assertEquals(index == game.lastIndex, tracker.onFrame(time) { counted.add(it) })
        }
        assertEquals(game, counted)

        val nextSession = GamescopeFrameStart()
        assertFalse(nextSession.onFrame(40_000L) { counted.add(it) })
        assertFalse(nextSession.gameContentSeen)
        assertEquals(game, counted)
    }
}
