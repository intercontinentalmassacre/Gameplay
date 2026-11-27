package app.gamenative.utils

import java.io.File
import java.io.FilenameFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CoverCacheDiskIndexTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun preexistingCoversAndRepeatedTrimsUseOneDirectoryListing() {
        var listings = 0
        val directory = object : File(temporary.root.path) {
            override fun listFiles(filter: FilenameFilter?): Array<File>? {
                listings++
                return super.listFiles(filter)
            }
        }
        val oldest = cover("oldest.jpg", 4, 1_000L)
        val next = cover("next.jpg", 4, 2_000L)
        val pending = cover("ongoing.tmp", 100, 3_000L)
        val index = CoverCache.DiskIndex(directory)
        assertEquals(8L, index.sizeBytes)

        val newest = cover("newest.jpg", 5, 4_000L)
        index.record(newest)
        index.trimTo(9)
        assertFalse(oldest.exists())
        assertEquals(9L, index.sizeBytes)

        val latest = cover("latest.jpg", 5, 5_000L)
        index.record(latest)
        index.trimTo(10)
        assertFalse(next.exists())
        assertTrue(newest.exists())
        assertTrue(latest.exists())
        assertTrue(pending.exists())
        assertEquals(10L, index.sizeBytes)
        assertEquals(1, listings)
    }

    @Test
    fun replacementAndExplicitDeletionUpdateBytesAndEvictionOrder() {
        val replaced = cover("replaced.jpg", 4, 1_000L)
        val older = cover("older.jpg", 4, 2_000L)
        val orphan = cover("orphan.jpg", 4, 3_000L)
        val index = CoverCache.DiskIndex(temporary.root)

        cover(replaced.name, 5, 4_000L)
        index.record(replaced)
        assertEquals(13L, index.sizeBytes)
        assertEquals(1, index.deleteWhere { it == orphan })
        assertFalse(orphan.exists())
        assertEquals(9L, index.sizeBytes)

        index.trimTo(5)
        assertFalse(older.exists())
        assertTrue(replaced.exists())
        assertEquals(5L, index.sizeBytes)
        assertEquals(1, index.deleteWhere { true })
        assertEquals(0L, index.sizeBytes)
    }

    private fun cover(name: String, bytes: Int, modified: Long): File = File(temporary.root, name).apply {
        writeBytes(ByteArray(bytes))
        assertTrue(setLastModified(modified))
    }
}
