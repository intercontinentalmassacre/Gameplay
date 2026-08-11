package app.gamenative.utils.downloader

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerFilesDownloaderArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts readable tar zstd archive`() {
        val archive = temporaryFolder.newFile("valid.tzst")
        writeArchive(archive)

        assertTrue(ContainerFilesDownloader.isValidCacheArchive(archive))
    }

    @Test
    fun `rejects non archive cache content`() {
        val archive = temporaryFolder.newFile("invalid.tzst")
        archive.writeText("partial download")

        assertFalse(ContainerFilesDownloader.isValidCacheArchive(archive))
    }

    private fun writeArchive(file: File) {
        val payload = "runtime payload".toByteArray()
        FileOutputStream(file).use { output ->
            ZstdCompressorOutputStream(BufferedOutputStream(output)).use { compressed ->
                TarArchiveOutputStream(compressed).use { archive ->
                    val entry = TarArchiveEntry("payload.txt")
                    entry.size = payload.size.toLong()
                    archive.putArchiveEntry(entry)
                    archive.write(payload)
                    archive.closeArchiveEntry()
                    archive.finish()
                }
            }
        }
    }
}
