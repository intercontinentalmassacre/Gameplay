package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortUtilsTest {

    private data class Entry(
        val name: String,
        val isInstalled: Boolean,
        val lastPlayed: Long,
    )

    @Test
    fun recentlyPlayedComparator_keepsInstalledFirstAndSortsEachGroupByLastPlayed() {
        val entries = listOf(
            Entry(name = "Uninstalled Recent", isInstalled = false, lastPlayed = 9000L),
            Entry(name = "Installed Older", isInstalled = true, lastPlayed = 1000L),
            Entry(name = "Installed Recent", isInstalled = true, lastPlayed = 5000L),
            Entry(name = "Uninstalled Older", isInstalled = false, lastPlayed = 3000L),
        )

        val sorted = entries.sortedWith(
            LibrarySortUtils.recentlyPlayedComparator(
                name = Entry::name,
                isInstalled = Entry::isInstalled,
                lastPlayed = Entry::lastPlayed,
            ),
        )

        assertEquals(
            listOf("Installed Recent", "Installed Older", "Uninstalled Recent", "Uninstalled Older"),
            sorted.map { it.name },
        )
    }

    @Test
    fun recentlyPlayedComparator_canOrderStrictlyByRecencyAcrossInstallStates() {
        val olderInstalled = Entry(name = "Installed", isInstalled = true, lastPlayed = 1000L)
        val newerUninstalled = Entry(name = "Uninstalled", isInstalled = false, lastPlayed = 5000L)
        val comparator = LibrarySortUtils.recentlyPlayedComparator(
            name = Entry::name,
            isInstalled = Entry::isInstalled,
            lastPlayed = Entry::lastPlayed,
            installedFirst = false,
        )

        assertEquals(
            listOf(newerUninstalled, olderInstalled),
            listOf(olderInstalled, newerUninstalled).sortedWith(comparator),
        )
    }

    @Test
    fun recentlyPlayedComparator_usesNameFallbackWhenLastPlayedMatches() {
        val entries = listOf(
            Entry(name = "Beta", isInstalled = false, lastPlayed = 0L),
            Entry(name = "alpha", isInstalled = false, lastPlayed = 0L),
            Entry(name = "Charlie", isInstalled = false, lastPlayed = 0L),
        )

        val sorted = entries.sortedWith(
            LibrarySortUtils.recentlyPlayedComparator(
                name = Entry::name,
                isInstalled = Entry::isInstalled,
                lastPlayed = Entry::lastPlayed,
            ),
        )

        assertEquals(listOf("alpha", "Beta", "Charlie"), sorted.map { it.name })
    }
}
