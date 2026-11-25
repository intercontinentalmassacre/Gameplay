package app.gamenative.ui.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamSaveTransferTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `archive entries use the longest matching save root`() {
        val parentRoot = temp.newFolder("parent")
        val nestedRoot = temp.newFolder("nested")
        val rootMap: Map<String, Path> = mapOf(
            "winmydocuments/game" to parentRoot.toPath(),
            "winmydocuments/game/profile" to nestedRoot.toPath(),
        )
        val archive = archive("files/winmydocuments/game/profile/save.sav" to "saved game")
        ZipFile(archive).use { zip ->
            assertTrue(SteamSaveTransfer.restoreArchive(zip, rootMap))
        }
        assertEquals("saved game", nestedRoot.resolve("save.sav").readText())
        assertFalse(parentRoot.resolve("profile/save.sav").exists())
    }

    @Test
    fun `archive entries without a root separator are skipped`() {
        ZipFile(archive("files/save.sav" to "saved game")).use { zip ->
            assertFalse(SteamSaveTransfer.restoreArchive(zip, emptyMap()))
        }
    }

    @Test
    fun `late bad CRC preserves every existing save`() {
        val root = temp.newFolder("saves")
        val first = root.resolve("first.sav").apply { writeText("first local save") }
        val second = root.resolve("second.sav").apply { writeText("second local save") }
        val latePayload = "second imported save"
        val archive = archive(
            "files/documents/first.sav" to "first imported save",
            "files/documents/second.sav" to latePayload,
        )
        val bytes = archive.readBytes()
        val payloadStart = bytes.toString(Charsets.ISO_8859_1).indexOf(latePayload)
        bytes[payloadStart] = 0
        archive.writeBytes(bytes)

        ZipFile(archive).use { zip ->
            assertThrows(ZipException::class.java) {
                SteamSaveTransfer.restoreArchive(zip, mapOf("documents" to root.toPath()))
            }
        }
        assertEquals("first local save", first.readText())
        assertEquals("second local save", second.readText())
        assertEquals(setOf(first.name, second.name), root.list()?.toSet())
    }

    @Test
    fun `failed replacement restores old saves and removes new saves`() {
        val root = temp.newFolder("saves")
        val first = root.resolve("first.sav").apply { writeText("first local save") }
        val third = root.resolve("third.sav").apply { writeText("third local save") }
        val archive = archive(
            "files/documents/first.sav" to "first imported save",
            "files/documents/second.sav" to "second imported save",
            "files/documents/third.sav" to "third imported save",
        )

        val replaced = mutableListOf<String>()
        ZipFile(archive).use { zip ->
            val error = assertThrows(IOException::class.java) {
                SteamSaveTransfer.restoreArchive(zip, mapOf("documents" to root.toPath())) { source, target ->
                    if (target.fileName.toString() == third.name) throw IOException("Cannot replace the last save")
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    replaced.add(target.fileName.toString())
                }
            }
            assertEquals("Cannot replace the last save", error.message)
        }
        assertEquals(listOf("first.sav", "second.sav"), replaced)
        assertEquals("first local save", first.readText())
        assertEquals("third local save", third.readText())
        assertEquals(setOf(first.name, third.name), root.list()?.toSet())
    }

    @Test
    fun `archive rejects normalized paths that target the same save`() {
        val root = temp.newFolder("saves")
        val save = root.resolve("save.sav").apply { writeText("local save") }
        val archive = archive(
            "files/documents/save.sav" to "first imported save",
            "files/documents/subdir/../save.sav" to "second imported save",
        )

        ZipFile(archive).use { zip ->
            assertThrows(ZipException::class.java) {
                SteamSaveTransfer.restoreArchive(zip, mapOf("documents" to root.toPath()))
            }
        }
        assertEquals("local save", save.readText())
        assertEquals(listOf(save.name), root.list()?.toList())
    }

    @Test
    fun `archive rejects a linked parent outside the save root before creating files`() {
        val root = temp.newFolder("saves")
        val outside = temp.newFolder("outside")
        val link = root.resolve("linked").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (e: IOException) {
            assumeNoException(e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        }
        val archive = archive("files/documents/linked/child/save.sav" to "imported save")

        ZipFile(archive).use { zip ->
            assertThrows(IOException::class.java) {
                SteamSaveTransfer.restoreArchive(zip, mapOf("documents" to root.toPath()))
            }
        }
        assertEquals(emptyList<String>(), outside.list()?.toList())
    }

    private fun archive(vararg entries: Pair<String, String>): File = temp.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, contents) in entries) {
                val payload = contents.toByteArray()
                zip.putNextEntry(ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = payload.size.toLong()
                    crc = CRC32().apply { update(payload) }.value
                })
                zip.write(payload)
                zip.closeEntry()
            }
        }
    }
}
