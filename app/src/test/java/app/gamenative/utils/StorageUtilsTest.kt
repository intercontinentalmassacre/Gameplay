package app.gamenative.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val defaultStorageUuid = UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69")

    @Test
    fun getFolderSize_countsHardlinkedFilesOnce() {
        val folder = tempFolder.newFolder("game")
        File(folder, "private.bin").writeBytes(ByteArray(1024))
        val original = File(folder, "mods/original.bin").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(4096))
        }
        createHardLink(File(folder, "linked.bin"), original)
        createHardLink(File(folder, "mods/linked-again.bin"), original)

        assertEquals(5120L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun getFolderSize_skipsFilesHardlinkedOutsideTheTree() {
        val folder = tempFolder.newFolder("container")
        val shared = tempFolder.newFolder("shared")
        File(folder, "private.bin").writeBytes(ByteArray(1024))
        val sharedFile = File(shared, "shared.bin").apply { writeBytes(ByteArray(4096)) }
        createHardLink(File(folder, "linked.bin"), sharedFile)
        // Two of three links in-tree is still not all of them.
        createHardLink(File(folder, "linked-again.bin"), sharedFile)

        assertEquals(1024L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun getFolderSize_sumsRegularFiles() {
        val folder = tempFolder.newFolder("game")
        val sub = File(folder, "sub").apply { mkdirs() }
        File(folder, "a.bin").writeBytes(ByteArray(1024))
        File(sub, "b.bin").writeBytes(ByteArray(2048))

        assertEquals(3072L, runBlocking { StorageUtils.getFolderSize(folder.absolutePath) })
    }

    @Test
    fun directorySizeOnDisk_doesNotFollowSymlinks() {
        val folder = tempFolder.newFolder("game")
        val outside = tempFolder.newFolder("workshop")
        File(folder, "game.exe").writeBytes(ByteArray(1024))
        File(outside, "mod.pak").writeBytes(ByteArray(8192))
        // GOG's _CommonRedist/ISI/rootdir points back at the install dir itself.
        File(folder, "_CommonRedist/ISI").mkdirs()
        createSymbolicLink(File(folder, "_CommonRedist/ISI/rootdir"), folder)
        createSymbolicLink(File(folder, "workshop"), outside)
        createSymbolicLink(File(folder, "mod-link.pak"), File(outside, "mod.pak"))

        assertEquals(1024L, StorageUtils.directorySizeOnDisk(folder.toPath()))
    }

    @Test
    fun directorySizeOnDisk_resolvesSymlinkedRoot() {
        val folder = tempFolder.newFolder("game")
        File(folder, "game.exe").writeBytes(ByteArray(1024))
        val link = File(tempFolder.root, "game-link")
        createSymbolicLink(link, folder)

        assertEquals(1024L, StorageUtils.directorySizeOnDisk(link.toPath()))
    }

    @Test
    fun `built-in primary storage is not a separate install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `default UUID overrides weaker physical and legacy signals`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = defaultStorageUuid,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `adopted primary storage is an install target`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = UUID.fromString("12345678-1234-1234-1234-123456789abc"),
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `legacy adopted primary falls back to its volume UUID`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `modern primary does not use a raw UUID when its typed UUID is unavailable`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = "ABCD-1234",
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `physical primary storage remains an install target when UUID lookup fails`() {
        assertTrue(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = true,
                allowLegacyUuidFallback = false,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    @Test
    fun `unidentified emulated primary storage is not an install target`() {
        assertFalse(
            StorageUtils.isNonDefaultPrimaryStorage(
                resolvedStorageUuid = null,
                legacyVolumeUuid = null,
                isPhysicalPrimary = false,
                allowLegacyUuidFallback = true,
                defaultStorageUuid = defaultStorageUuid,
            ),
        )
    }

    private fun createHardLink(link: File, target: File) {
        try {
            Files.createLink(link.toPath(), target.toPath())
            // The counting helper keys hard links on unix:nlink, which not every default
            // filesystem provider exposes (notably Windows), so skip rather than mis-assert.
            Files.getAttribute(link.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            assumeNoException("Hard links are unsupported here", e)
        }
    }

    private fun createSymbolicLink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks are unsupported here", e)
        }
    }
}
