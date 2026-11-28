package app.gamenative.service

import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanGameReceiverTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun streamsNestedFilesAndRecoversFromAnInterruptedUpload() {
        val library = temp.newFolder()
        val completed = ArrayBlockingQueue<File>(1)
        LanGameReceiver(InetAddress.getByName("127.0.0.1"), library, "Upload page".toByteArray()) {
            completed.add(it)
        }.use { receiver ->
            assertEquals(200, request(receiver, "GET /", key = null))
            assertEquals(403, request(receiver, "POST /start?name=Game", key = null))
            assertEquals(204, request(receiver, "POST /start?name=Game%20Folder"))
            assertEquals(204, request(receiver, "POST /start?name=Game%20Folder"))
            assertEquals(409, request(receiver, "POST /start?name=Game%20Folder", transferId = "b".repeat(32)))
            assertEquals(409, request(receiver, "POST /cancel", transferId = "b".repeat(32)))
            assertEquals(409, request(receiver, "POST /finish"))
            assertEquals(400, request(receiver, "PUT /file?path=%2e%2e%2fescaped.exe"))
            assertEquals(204, request(receiver, "PUT /file?path=Bin%2Fgame.exe", body = "executable"))
            assertEquals(204, request(receiver, "PUT /file?path=Bin%2Fgame.exe", body = "executable"))
            assertEquals(500, request(receiver, "PUT /file?path=data.dat", body = "partial", declaredLength = 10))
            assertEquals(204, request(receiver, "PUT /file?path=data.dat", body = "whole file"))
            assertEquals(204, request(receiver, "PUT /file?path=empty.txt"))
            assertEquals(204, request(receiver, "POST /finish"))

            val folder = completed.poll(5, TimeUnit.SECONDS)
            assertNotNull(folder)
            assertEquals("Game Folder", folder!!.name)
            assertEquals("executable", File(folder, "Bin/game.exe").readText())
            assertEquals("whole file", File(folder, "data.dat").readText())
            assertEquals(0L, File(folder, "empty.txt").length())
            assertEquals(listOf(folder.parentFile.name), library.list()!!.toList())
            assertFalse(File(library, "escaped.exe").exists())
        }
    }

    @Test
    fun checksFolderSizeBeforeReceivingAndTracksConfirmedBytesOnRetry() {
        val library = temp.newFolder()
        val progress = ArrayBlockingQueue<Pair<Long, Long?>>(16)
        LanGameReceiver(InetAddress.getLoopbackAddress(), library, byteArrayOf(),
            onProgress = { bytes, total -> progress.add(bytes to total) },
        ) {}.use { receiver ->
            assertEquals(507, request(receiver, "POST /start?name=Large", folderSize = Long.MAX_VALUE))
            assertTrue(library.listFiles()!!.isEmpty())
            assertEquals(400, request(receiver, "POST /start?name=Bad", folderSize = -1))
            assertEquals(204, request(receiver, "POST /start?name=Game", folderSize = 4))
            assertEquals(0L to 4L, progress.poll())
            assertEquals(204, request(receiver, "PUT /file?path=a", body = "ab"))
            assertEquals(204, request(receiver, "PUT /file?path=a", body = "ab"))
            assertEquals(409, request(receiver, "POST /finish"))
            assertEquals(409, request(receiver, "PUT /file?path=b", body = "abc"))
            assertEquals(204, request(receiver, "PUT /file?path=b", body = "cd"))
            assertEquals(204, request(receiver, "POST /finish"))
            assertEquals(4L to 4L, progress.last())
        }
    }

    @Test
    fun cancelDiscardsFilesAndAllowsAnotherTransfer() {
        val library = temp.newFolder()
        LanGameReceiver(InetAddress.getByName("127.0.0.1"), library, byteArrayOf()) {
            error("A canceled transfer must not publish a game")
        }.use { receiver ->
            assertEquals(204, request(receiver, "POST /start?name=Game"))
            assertEquals(204, request(receiver, "PUT /file?path=game.exe", body = "data"))
            assertEquals(204, request(receiver, "POST /cancel"))
            assertEquals(0, library.listFiles()!!.size)
            assertEquals(204, request(receiver, "POST /start?name=Other%20game"))
            assertEquals(204, request(receiver, "POST /cancel"))
            assertEquals(0, library.listFiles()!!.size)
        }
    }

    @Test
    fun newReceiverCleansCrashStagingAndLeavesActiveTransfersAlone() {
        val library = temp.newFolder()
        val stale = File(library, ".incoming-old").apply { mkdir() }
        File(stale, "large partial").writeText("unfinished")
        val retained = File(library, "finished-game").apply { mkdir() }
        val address = InetAddress.getByName("127.0.0.1")
        LanGameReceiver(address, library, byteArrayOf()) {}.use { first ->
            assertFalse(stale.exists())
            assertTrue(retained.exists())
            assertEquals(204, request(first, "POST /start?name=Game"))
            assertEquals(204, request(first, "PUT /file?path=game.exe", body = "data"))

            LanGameReceiver(address, library, byteArrayOf()) {}.use {
                assertEquals(204, request(first, "PUT /file?path=another.exe", body = "data"))
            }
            assertEquals(204, request(first, "POST /cancel"))
            assertEquals(listOf(retained), library.listFiles()!!.toList())
        }
    }

    @Test
    fun updatesInPlaceOnlyAfterEveryChangedFileIsVerified() {
        val library = temp.newFolder()
        val completed = ArrayBlockingQueue<File>(2)
        val address = InetAddress.getLoopbackAddress()
        LanGameReceiver(address, library, byteArrayOf()) { completed.add(it) }.use { receiver ->
            assertEquals(204, request(receiver, "POST /start?name=Game"))
            assertEquals(204, request(receiver, "PUT /file?path=game.exe", body = "old"))
            assertEquals(204, request(receiver, "PUT /file?path=data", body = "same"))
            assertEquals(204, request(receiver, "PUT /file?path=settings", body = "default"))
            assertEquals(204, request(receiver, "PUT /file?path=retired", body = "preserve"))
            assertEquals(204, request(receiver, "POST /finish"))
        }
        val game = checkNotNull(completed.poll(5, TimeUnit.SECONDS))
        File(game, "save").writeText("local")
        File(game, "settings").writeText("custom")

        LanGameReceiver(address, library, byteArrayOf(), updateFolder = game) { completed.add(it) }.use { receiver ->
            assertEquals(200, request(receiver, "GET /mode"))
            assertEquals(409, request(receiver, "POST /start?name=Other", folderFiles = 4))
            assertEquals(204, request(receiver, "POST /start?name=Game", folderFiles = 5))
            assertEquals(204, checkFile(receiver, "data", "same"))
            assertEquals(204, checkFile(receiver, "settings", "default"))
            assertEquals(204, checkFile(receiver, "save", "local"))
            assertEquals(200, checkFile(receiver, "game.exe", "new"))
            assertEquals(409, request(receiver, "PUT /file?path=game.exe", body = "bad"))
            assertEquals(204, request(receiver, "PUT /file?path=game.exe", body = "new"))
            assertEquals(200, checkFile(receiver, "game.exe", "new"))
            assertEquals(204, request(receiver, "PUT /file?path=game.exe", body = "new"))
            assertEquals(200, checkFile(receiver, "extra%2Ffile", "whole"))
            assertEquals(500, request(receiver, "PUT /file?path=extra%2Ffile", body = "part", declaredLength = 5))
            assertEquals(409, request(receiver, "POST /finish"))
            assertEquals("old", File(game, "game.exe").readText())
            assertFalse(File(game, "extra").exists())
            assertEquals(204, request(receiver, "PUT /file?path=extra%2Ffile", body = "whole"))
            assertEquals(204, request(receiver, "POST /finish"))
        }
        assertEquals(game, completed.poll(5, TimeUnit.SECONDS))
        assertEquals("new", File(game, "game.exe").readText())
        assertEquals("whole", File(game, "extra/file").readText())
        assertEquals("local", File(game, "save").readText())
        assertEquals("custom", File(game, "settings").readText())
        assertEquals("preserve", File(game, "retired").readText())
        assertEquals(listOf(game.parentFile.name), library.list()!!.toList())
        LanGameReceiver(address, library, byteArrayOf(), updateFolder = game) {
            error("An exact match must not adopt an untracked local save in a managed import")
        }.use { receiver ->
            assertEquals(204, request(receiver, "POST /start?name=Game", folderFiles = 1))
            assertEquals(409, checkFile(receiver, "save", "different"))
            assertEquals(204, request(receiver, "POST /cancel"))
        }
    }

    @Test
    fun protectsUntrackedLegacyFilesAndDetectsLateLocalEdits() {
        val library = temp.newFolder()
        val game = File(library, "${UUID.randomUUID()}/Game").apply { mkdirs() }
        File(game, "game.exe").writeText("old")
        val completed = ArrayBlockingQueue<File>(1)
        val address = InetAddress.getLoopbackAddress()
        LanGameReceiver(address, library, byteArrayOf(), updateFolder = game) { completed.add(it) }.use { receiver ->
            assertEquals(204, request(receiver, "POST /start?name=Game", folderFiles = 1))
            assertEquals(409, checkFile(receiver, "game.exe", "new"))
            assertEquals(409, request(receiver, "POST /finish"))
            assertEquals(204, checkFile(receiver, "game.exe", "old"))
            assertEquals(204, request(receiver, "POST /finish"))
        }
        assertEquals(game.canonicalFile, completed.poll(5, TimeUnit.SECONDS))
        LanGameReceiver(address, library, byteArrayOf(), updateFolder = game) {
            error("A conflict must prevent publishing the update")
        }.use { receiver ->
            assertEquals(204, request(receiver, "POST /start?name=Game", folderFiles = 1))
            assertEquals(200, checkFile(receiver, "game.exe", "new"))
            assertEquals(204, request(receiver, "PUT /file?path=game.exe", body = "new"))
            File(game, "game.exe").writeText("local")
            assertEquals(409, request(receiver, "POST /finish"))
            assertEquals(204, request(receiver, "POST /cancel"))
        }
        assertEquals("local", File(game, "game.exe").readText())
    }

    @Test
    fun recoversAnInterruptedCommitAndKeepsACompletedCommit() {
        val library = temp.newFolder()
        val game = File(library, "installed/Game").apply { mkdirs() }
        val changed = File(game, "game.exe").apply { writeText("new") }
        val added = File(game, "extra/file").apply {
            parentFile.mkdirs()
            writeText("added")
        }

        fun createJournal(pending: Boolean): File {
            val backup = File(library, ".update-recovery-test").apply { mkdir() }
            File(backup, "0").writeText("old")
            if (pending) File(backup, ".wifi-import-sha256").writeText("prepared manifest")
            val journal = Properties().apply {
                setProperty("folder", "installed/Game")
                setProperty("files", "2")
                setProperty("path.0", "game.exe")
                setProperty("old.0", digest("old"))
                setProperty("new.0", digest("new"))
                setProperty("path.1", "extra/file")
                setProperty("new.1", digest("added"))
                setProperty("directories", "1")
                setProperty("directory.0", "extra")
            }
            File(backup, "journal").outputStream().use { journal.store(it, null) }
            return backup
        }

        val pending = createJournal(pending = true)
        LanGameReceiver.recoverUpdates(library)
        assertEquals("old", changed.readText())
        assertFalse(added.parentFile.exists())
        assertFalse(pending.exists())

        changed.writeText("new")
        val committed = createJournal(pending = false)
        LanGameReceiver.recoverUpdates(library)
        assertEquals("new", changed.readText())
        assertFalse(committed.exists())
    }

    private fun checkFile(receiver: LanGameReceiver, path: String, contents: String): Int =
        request(receiver, "POST /check?path=$path", fileDigest = digest(contents), fileSize = contents.toByteArray().size)

    private fun digest(contents: String): String =
        MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun request(
        receiver: LanGameReceiver,
        requestLine: String,
        key: String? = receiver.pairingCode,
        body: String = "",
        declaredLength: Int = body.toByteArray().size,
        transferId: String = "a".repeat(32),
        folderSize: Long? = null,
        folderFiles: Int? = null,
        fileDigest: String? = null,
        fileSize: Int? = null,
    ): Int {
        val uri = URI(receiver.url)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            val headers = buildString {
                append("$requestLine HTTP/1.1\r\nHost: ${uri.authority}\r\n")
                if (key != null) append("X-Transfer-Key: $key\r\n")
                append("X-Transfer-Id: $transferId\r\n")
                if (folderSize != null) append("X-Folder-Bytes: $folderSize\r\n")
                if (folderFiles != null) append("X-Folder-Files: $folderFiles\r\n")
                if (fileDigest != null) append("X-File-Sha256: $fileDigest\r\n")
                if (fileSize != null) append("X-File-Bytes: $fileSize\r\n")
                append("Content-Length: $declaredLength\r\n\r\n")
            }
            socket.getOutputStream().write((headers + body).toByteArray())
            socket.shutdownOutput()
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
    }
}
