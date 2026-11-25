package app.gamenative.service.amazon

import java.security.MessageDigest
import java.io.DataOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AmazonDownloadManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `discarding a staged update leaves the installed files intact`() {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val update = AmazonFileUpdate(temp.root)
        update.stageFile(destination.canonicalFile).apply {
            parentFile!!.mkdirs()
            writeText("new")
        }

        update.cleanUp()

        assertEquals("old", destination.readText())
        assertEquals(listOf("game.dat"), temp.root.list()!!.toList())
    }

    @Test
    fun `failure to record the new version rolls back replaced and added files`() = runBlocking {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val added = temp.root.resolve("new.dat")
        val update = AmazonFileUpdate(temp.root)
        update.stageFile(destination.canonicalFile).apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        update.stageFile(added.canonicalFile).writeText("added")

        val result = runCatching {
            update.commit("new-version", 8, byteArrayOf(1), markInstalled = { _, _ ->
                assertEquals("new", destination.readText())
                assertTrue(added.exists())
                error("database write failed")
            }, finish = { error("Failed updates must not replace the manifest") })
        }
        update.cleanUp()

        assertEquals("database write failed", result.exceptionOrNull()?.message)
        assertEquals("old", destination.readText())
        assertFalse(added.exists())
    }

    @Test
    fun `interrupted finalization replays the committed version and manifest`() = runBlocking {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val update = AmazonFileUpdate(temp.root)
        update.stageFile(destination.canonicalFile).apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        val committed = mutableListOf<Pair<String, Long>>()

        runCatching {
            update.commit("same-or-new-version", 3, byteArrayOf(1), markInstalled = { version, size ->
                committed += version to size
            }, finish = { throw AssertionError("process stopped after Room committed") })
        }
        update.cleanUp()

        var cachedManifest: List<Byte> = emptyList()
        repeat(2) {
            AmazonFileUpdate.recover(temp.root, markInstalled = { version, size ->
                committed += version to size
            }, finish = { cachedManifest = it.readBytes().toList() })
        }

        assertEquals(listOf("same-or-new-version" to 3L, "same-or-new-version" to 3L), committed)
        assertEquals(listOf<Byte>(1), cachedManifest)
        assertEquals("new", destination.readText())
        assertEquals(listOf("game.dat"), temp.root.list()!!.toList())
    }

    @Test
    fun `recovery before the ready marker restores old files and removes newly added files`() = runBlocking {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val added = temp.root.resolve("new.dat")
        val update = AmazonFileUpdate(temp.root)
        update.stageFile(destination.canonicalFile).apply {
            parentFile!!.mkdirs()
            writeText("new")
        }
        update.stageFile(added.canonicalFile).writeText("added")
        runCatching {
            update.commit("new-version", 8, byteArrayOf(1), markInstalled = { _, _ ->
                throw AssertionError("process stopped before metadata publication")
            }, finish = { error("Unexpected finalization") })
        }
        val workDir = temp.root.listFiles()!!.single { it.isDirectory }
        check(workDir.resolve("ready").delete())
        update.cleanUp()

        repeat(2) {
            AmazonFileUpdate.recover(
                temp.root,
                markInstalled = { _, _ -> error("The previous version must be kept") },
                finish = { error("The previous manifest must be kept") },
            )
        }

        assertEquals("old", destination.readText())
        assertFalse(added.exists())
        assertEquals(listOf("game.dat"), temp.root.list()!!.toList())
    }

    @Test
    fun `recovery leaves old and unrelated directories alone`() = runBlocking {
        val unrelated = listOf(
            ".AmazonUpdate-backup",
            ".AmazonUpdateDone-backup",
            ".GameNativeAmazonUpdate-v1-unrelated",
            ".GameNativeAmazonUpdate-v1-74d00576-7f70-4796-b2ea-2cea1e5be657",
            ".GameNativeAmazonUpdateDone-v1-1ef61140-6d27-4ec5-8922-bbb063b3c11a",
        ).map { name -> temp.newFolder(name).resolve("save").apply { writeText("keep") } }
        val update = AmazonFileUpdate(temp.root)
        val owned = temp.root.listFiles()!!.single { it.resolve("gamenative-owner").isFile }

        AmazonFileUpdate.recover(
            temp.root,
            markInstalled = { _, _ -> error("Uncommitted work must not be published") },
            finish = { error("Uncommitted work must not replace the manifest") },
        )
        update.cleanUp()

        unrelated.forEach { assertEquals("keep", it.readText()) }
        assertFalse(owned.exists())
    }

    @Test
    fun `journal rejects impossible entry count`() = runBlocking {
        AmazonFileUpdate(temp.root)
        val owned = temp.root.listFiles()!!.single()
        DataOutputStream(owned.resolve("journal").outputStream()).use {
            it.writeUTF("new-version")
            it.writeLong(3)
            it.writeInt(Int.MAX_VALUE)
        }

        val result = runCatching {
            AmazonFileUpdate.recover(temp.root, markInstalled = { _, _ -> }, finish = {})
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("entry count"))
        assertTrue(owned.exists())
    }

    @Test
    fun `changed file of the same size is downloaded again`() {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val manifestFile = AmazonManifest.ManifestFile(
            path = destination.name,
            size = 3,
            hashAlgorithm = 0,
            hashBytes = MessageDigest.getInstance("SHA-256").digest("new".toByteArray()),
        )

        assertFalse(canReuseAmazonFile(destination, manifestFile, versionChanged = true))
        destination.writeText("new")
        assertTrue(canReuseAmazonFile(destination, manifestFile, versionChanged = true))
    }

    @Test
    fun `file with an unsupported checksum gets refreshed when version changes`() {
        val destination = temp.newFile("game.dat").apply { writeText("old") }
        val manifestFile = AmazonManifest.ManifestFile(
            path = destination.name,
            size = 3,
            hashAlgorithm = 1,
            hashBytes = byteArrayOf(1),
        )

        assertFalse(canReuseAmazonFile(destination, manifestFile, versionChanged = true))
        assertTrue(canReuseAmazonFile(destination, manifestFile, versionChanged = false))
    }
}
