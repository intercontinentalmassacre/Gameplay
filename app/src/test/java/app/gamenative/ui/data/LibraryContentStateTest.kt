package app.gamenative.ui.data

import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.SortOption
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryContentStateTest {
    @Test
    fun `prioritizes loading and error before empty states`() {
        assertEquals(
            LibraryContentState.LOADING,
            libraryContentState(isLoading = true, hasItems = false, loadError = false, isSearching = false),
        )
        assertEquals(
            LibraryContentState.ERROR,
            libraryContentState(isLoading = false, hasItems = false, loadError = true, isSearching = false),
        )
        assertEquals(
            LibraryContentState.SEARCH_NO_RESULTS,
            libraryContentState(isLoading = false, hasItems = false, loadError = false, isSearching = true),
        )
        assertEquals(
            LibraryContentState.NO_RESULTS,
            libraryContentState(isLoading = false, hasItems = false, loadError = false, isSearching = false),
        )
        assertEquals(
            LibraryContentState.CONTENT,
            libraryContentState(isLoading = false, hasItems = true, loadError = false, isSearching = false),
        )
    }

    @Test
    fun `tab counts expose installed and catalog totals`() {
        val counts = libraryTabCounts(installed = 7, all = 42)

        assertEquals(7, counts[LibraryTab.INSTALLED])
        assertEquals(42, counts[LibraryTab.ALL])
    }

    @Test
    fun `Recent uses the recently played sort while Favorites uses the library sort`() {
        assertEquals(SortOption.RECENTLY_PLAYED, LibraryTab.RECENT.fixedSortOption ?: SortOption.NAME_ASC)
        assertEquals(SortOption.NAME_ASC, LibraryTab.FAVORITES.fixedSortOption ?: SortOption.NAME_ASC)
    }

    @Test
    fun `history tabs keep editions of the same game distinct`() {
        val steamEdition = "STEAM_123"
        val gogEdition = "GOG_456"
        val favorites = setOf(gogEdition)

        assertEquals(listOf(gogEdition), listOf(steamEdition, gogEdition).filter {
            LibraryTab.FAVORITES.matchesEntry(it, lastPlayed = 0L, favoriteGameIds = favorites)
        })
        assertEquals(listOf(gogEdition), listOf(steamEdition, gogEdition).filter {
            LibraryTab.RECENT.matchesEntry(it, lastPlayed = if (it == gogEdition) 1000L else 0L, favoriteGameIds = emptySet())
        })
    }

    @Test
    fun `pagination requests another page only at the loaded boundary`() {
        assertEquals(true, shouldLoadNextLibraryPage(lastVisibleIndex = 19, loadedItemCount = 20, totalItemCount = 45))
        assertEquals(false, shouldLoadNextLibraryPage(lastVisibleIndex = 18, loadedItemCount = 20, totalItemCount = 45))
        assertEquals(false, shouldLoadNextLibraryPage(lastVisibleIndex = 19, loadedItemCount = 20, totalItemCount = 20))
        assertEquals(false, shouldLoadNextLibraryPage(lastVisibleIndex = null, loadedItemCount = 20, totalItemCount = 45))
    }
}
