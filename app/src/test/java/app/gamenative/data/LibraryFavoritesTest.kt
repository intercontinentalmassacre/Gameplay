package app.gamenative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryFavoritesTest {
    @Test
    fun playNextReplacesThePreviousPinAndUnfavoriteClearsIt() {
        val steam = "STEAM_42"
        val gog = "GOG_42"
        val epic = "EPIC_42"
        val favorites = LibraryFavorites().toggleFavorite(steam).toggleFavorite(gog).togglePlayNext(steam)

        assertEquals(favorites, favorites.togglePlayNext(epic))
        assertEquals(steam, favorites.playNextGameId)
        val moved = favorites.togglePlayNext(gog)
        assertEquals(gog, moved.playNextGameId)
        assertNull(moved.togglePlayNext(gog).playNextGameId)
        assertEquals(moved, moved.toggleFavorite(gog).toggleFavorite(gog).togglePlayNext(gog))

        val removed = moved.toggleFavorite(gog)
        assertEquals(setOf(steam), removed.gameIds)
        assertNull(removed.playNextGameId)
    }
}
