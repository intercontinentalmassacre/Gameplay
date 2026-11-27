package app.gamenative.utils

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomGameLocalDiscoveryTest {
    @get:Rule val folders = TemporaryFolder()

    @Before
    @After
    fun resetCache() {
        CustomGameCache.invalidate()
    }

    @Test
    fun unchangedCandidatesReuseCacheAndChangedCandidatesRefreshIt() {
        val first = folders.newFolder("first")
        val second = folders.newFolder("second")
        var reads = 0
        val readId: (File) -> Int = { file ->
            reads++
            if (file == first) 100 else 200
        }
        val candidates = setOf(first.absolutePath)
        val initial = CustomGameCache.getOrRebuildCache(candidates, readId)

        CustomGameCache.addEntry(100, first.absolutePath)
        assertSame(initial, CustomGameCache.getOrRebuildCache(candidates.toMutableSet(), readId))
        assertEquals(1, reads)

        val updated = CustomGameCache.getOrRebuildCache(setOf(second.absolutePath), readId)
        assertEquals(mapOf(200 to second.absolutePath), updated)
        assertEquals(2, reads)

        CustomGameCache.invalidate()
        val refreshed = CustomGameCache.getOrRebuildCache(setOf(second.absolutePath)) { 300 }
        assertEquals(mapOf(300 to second.absolutePath), refreshed)
    }

    @Test
    fun uniqueExeUsesOneRootListingAndSkipsChildrenAfterTwoMatches() {
        val first = folders.newFile("first.exe")
        val second = folders.newFile("second.EXE")
        val child = ListingFile(folders.newFolder("child"), emptyArray())
        val root = ListingFile(folders.root, arrayOf(first, second, child))

        assertNull(CustomGameScanner.findUniqueExeRelativeToFolder(root))
        assertEquals(1, root.listings)
        assertEquals(0, child.listings)
    }

    @Test
    fun executableDiscoverySeesCurrentEntriesAndOnlySearchesOneLevelDown() {
        folders.newFile("uNiNs001.EXE")
        folders.newFolder("folder.exe")
        val child = folders.newFolder("child")
        val deep = File(child, "deep").apply { mkdir() }
        File(deep, "ignored.exe").createNewFile()
        val first = File(child, "first.EXE").apply { createNewFile() }

        assertEquals("child/first.EXE", CustomGameScanner.findUniqueExeRelativeToFolder(folders.root))
        val second = folders.newFile("second.exe")
        assertNull(CustomGameScanner.findUniqueExeRelativeToFolder(folders.root))
        assertEquals(
            listOf("second.exe", "child/first.EXE"),
            CustomGameScanner.findAllValidExeFiles(folders.root),
        )
        first.delete()
        assertEquals(second.name, CustomGameScanner.findUniqueExeRelativeToFolder(folders.root))
    }

    private class ListingFile(folder: File, private val entries: Array<File>) : File(folder.path) {
        var listings = 0
            private set

        override fun listFiles(): Array<File> {
            listings++
            return entries
        }
    }
}
