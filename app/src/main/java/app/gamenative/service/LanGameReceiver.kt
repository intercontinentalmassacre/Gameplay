package app.gamenative.service

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
class LanGameReceiver(
    bindAddress: InetAddress,
    libraryDirectory: File,
    private val uploadPage: ByteArray,
    private val onProgress: (Long, Long?) -> Unit = { _, _ -> },
    private val updateFolder: File? = null,
    private val onComplete: (File) -> Unit,
) : Closeable {
    private val libraryDirectory = libraryDirectory.canonicalFile
    private val installedFolder = updateFolder?.absoluteFile?.let { requested ->
        val parent = requireNotNull(requested.parentFile)
        require(runCatching { UUID.fromString(parent.name).toString() == parent.name }.getOrDefault(false))
        val rootedParent = File(this.libraryDirectory, parent.name)
        val folder = File(rootedParent, requested.name)
        require(folder.name != manifestName && folder.name !in setOf(".", "..") &&
            listOf(parent, requested, rootedParent, folder).all { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) } &&
            Files.isSameFile(parent.toPath(), rootedParent.toPath()) &&
            Files.isSameFile(requested.toPath(), folder.toPath()))
        folder
    }
    private val lock = Any()
    val pairingCode: String = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    private val listener = ServerSocket().apply {
        try {
            synchronized(stagingDirectories) {
                this@LanGameReceiver.libraryDirectory.listFiles()?.forEach { entry ->
                    if (entry.name.startsWith(".incoming-") && entry !in stagingDirectories) {
                        runCatching { entry.toPath().deleteRecursively() }
                    }
                }
            }
            recoverUpdates(this@LanGameReceiver.libraryDirectory)
            bind(InetSocketAddress(bindAddress, 0))
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private val host = bindAddress.hostAddress!!.let { if (it.contains(':')) "[$it]" else it }
    private val authority = "$host:${listener.localPort}"
    val url: String = "http://$authority/"

    private var client: Socket? = null
    private var closed = false

    // Transfer state belongs to the worker. Closing a socket interrupts a pending read.
    private var transfer: Transfer? = null
    private var completed: File? = null

    init {
        Thread(::receive, "LAN game receiver").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { listener.close() }
            runCatching { client?.close() }
        }
    }

    private fun receive() {
        try {
            while (completed == null) {
                val socket = try {
                    listener.accept()
                } catch (_: IOException) {
                    break
                }
                try {
                    synchronized(lock) {
                        if (closed) return
                        client = socket
                    }
                    socket.soTimeout = 3_000
                    handle(socket)
                } catch (_: IOException) {
                    // A disconnected browser can retry on a fresh connection.
                } finally {
                    synchronized(lock) {
                        client = null
                        runCatching { socket.close() }
                    }
                }
            }
        } finally {
            clearTransfer()
            close()
            completed?.let(onComplete)
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().buffered()
        val output = socket.getOutputStream()
        try {
            val request = readRequest(input)
            socket.soTimeout = 15_000
            if (request.headers["host"] != authority ||
                request.headers["origin"]?.let { it != "http://$authority" } == true
            ) {
                throw RequestError("403 Forbidden", "Use the receiver address shown in Gameplay.")
            }
            if (request.method == "GET" && request.target == "/") {
                respond(output, "200 OK", uploadPage, "text/html; charset=utf-8")
                return
            }
            val token = request.headers["x-transfer-key"].orEmpty()
            if (!MessageDigest.isEqual(pairingCode.toByteArray(), token.toByteArray())) {
                // A receiver handles one connection at a time, so guesses cannot run in parallel.
                if (token.isNotEmpty()) Thread.sleep(500)
                throw RequestError("403 Forbidden", "The pairing code is missing or incorrect.")
            }

            if ("transfer-encoding" in request.headers) {
                throw RequestError("400 Bad Request", "Send a fixed-length request.")
            }
            val transferId = request.headers["x-transfer-id"]
            if (transferId == null || !transferIdPattern.matches(transferId)) {
                throw RequestError("400 Bad Request", "The transfer ID is missing or invalid.")
            }
            if (transfer?.let { it.id != transferId } == true) {
                throw RequestError("409 Conflict", "Another transfer is active. Restart the receiver in Gameplay.")
            }
            when {
                request.method == "GET" && request.target == "/mode" -> {
                    val folder = installedFolder
                    if (folder == null) respond(output, "204 No Content")
                    else respond(output, "200 OK", folder.name.toByteArray(Charsets.UTF_8))
                    return
                }

                request.method == "POST" && request.target.startsWith("/start?name=") ->
                    startTransfer(transferId, decode(request.target.substringAfter('=')), request.headers)

                request.method == "POST" && request.target.startsWith("/check?path=") -> {
                    if (checkFile(decode(request.target.substringAfter('=')), request.headers)) {
                        respond(output, "200 OK", "upload".toByteArray())
                        return
                    }
                }

                request.method == "PUT" && request.target.startsWith("/file?path=") -> {
                    val length = request.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
                        ?: throw RequestError("411 Length Required", "The file size is missing or invalid.")
                    saveFile(decode(request.target.substringAfter('=')), length, input)
                }

                request.method == "POST" && request.target == "/finish" -> finishTransfer()
                request.method == "POST" && request.target == "/cancel" -> {
                    clearTransfer()
                    onProgress(0, null)
                }

                else -> throw RequestError("404 Not Found", "This transfer endpoint does not exist.")
            }
            respond(output, "204 No Content")
        } catch (error: RequestError) {
            respond(output, error.status, error.message.toByteArray(Charsets.UTF_8))
        }
    }

    private fun startTransfer(transferId: String, name: String, headers: Map<String, String>) {
        val installed = installedFolder
        val fileCount = installed?.let {
            if (name != it.name) throw RequestError("409 Conflict", "Select the original folder named ${it.name}.")
            headers["x-folder-files"]?.toIntOrNull()?.takeIf { count -> count > 0 }
                ?: throw RequestError("400 Bad Request", "Invalid folder file count.")
        }
        val totalBytes = headers["x-folder-bytes"]?.takeIf { installed == null }?.let {
            it.toLongOrNull()?.takeIf { size -> size >= 0 }
                ?: throw RequestError("400 Bad Request", "Invalid folder size.")
        }
        if (transfer?.folder?.name == name && transfer?.totalBytes == totalBytes &&
            transfer?.expectedFiles == fileCount
        ) return
        if (transfer != null) {
            throw RequestError("409 Conflict", "Cancel the current transfer before starting another.")
        }
        validateSegment(name)
        if (name == manifestName) throw RequestError("400 Bad Request", "This game folder name is reserved.")
        try {
            Files.createDirectories(libraryDirectory.toPath())
            val baseline = installed?.let(::loadManifest) ?: Properties()
            if (totalBytes != null && totalBytes > libraryDirectory.usableSpace) {
                throw RequestError("507 Insufficient Storage", "Not enough space for this folder.")
            }
            val staging = synchronized(stagingDirectories) {
                Files.createTempDirectory(libraryDirectory.toPath(), ".incoming-").toFile()
                    .also { stagingDirectories.add(it) }
            }
            val folder = File(staging, name)
            if (!folder.mkdir()) {
                discard(staging)
                throw IOException("Cannot create the game folder")
            }
            transfer = Transfer(transferId, staging, folder, totalBytes, fileCount, baseline)
            onProgress(0, totalBytes)
        } catch (_: IOException) {
            throw RequestError("507 Insufficient Storage", "Gameplay could not create the game folder.")
        }
    }

    private fun saveFile(relativePath: String, length: Long, input: InputStream) {
        val active = requireTransfer()
        validatePath(relativePath)
        val plan = if (active.expectedFiles == null) {
            null
        } else {
            active.plans[relativePath]?.takeIf { it.upload && it.length == length }
                ?: throw RequestError("409 Conflict", "Check this file before uploading it.")
        }
        val target = File(active.folder, relativePath)
        try {
            if (!target.canonicalFile.toPath().startsWith(active.folder.canonicalFile.toPath())) {
                throw RequestError("400 Bad Request", "The file path is outside the game folder.")
            }
            if (active.files[relativePath]?.length == length) {
                val repeated = copyFile(input, null, length) {}
                if (repeated != active.files[relativePath]?.digest) {
                    throw RequestError("409 Conflict", "A previous upload used different contents for this path.")
                }
                return
            }
            if (target.exists()) {
                throw RequestError("409 Conflict", "A file or folder already uses this path.")
            }
            if (active.totalBytes != null && length > active.totalBytes - active.bytes) {
                throw RequestError("409 Conflict", "File exceeds the remaining folder size.")
            }
            if (length > active.folder.usableSpace) {
                throw RequestError("507 Insufficient Storage", "There is not enough free space for this file.")
            }

            Files.createDirectories(target.parentFile!!.toPath())
            val digest = try {
                target.outputStream().buffered().use { output ->
                    copyFile(input, output, length) { copied ->
                        onProgress(active.bytes + copied, active.totalBytes)
                    }
                }
            } catch (error: IOException) {
                target.delete()
                onProgress(active.bytes, active.totalBytes)
                throw error
            }
            if (plan != null && digest != plan.digest) {
                target.delete()
                onProgress(active.bytes, active.totalBytes)
                throw RequestError("409 Conflict", "The file changed after it was checked. Start the update again.")
            }
            active.bytes += length
            active.files[relativePath] = UploadedFile(length, digest)
            onProgress(active.bytes, active.totalBytes)
        } catch (_: IOException) {
            throw RequestError("500 Internal Server Error", "Gameplay could not save the complete file.")
        }
    }

    private fun copyFile(input: InputStream, output: OutputStream?, length: Long, progress: (Long) -> Unit): String {
        val buffer = ByteArray(64 * 1024)
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = length
        var lastUpdate = System.nanoTime()
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("The browser disconnected during the upload")
            output?.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            remaining -= read
            val now = System.nanoTime()
            if (now - lastUpdate >= 250_000_000L) {
                progress(length - remaining)
                lastUpdate = now
            }
        }
        return hex(digest.digest())
    }

    private fun finishTransfer() {
        val active = requireTransfer()
        if (installedFolder != null) {
            finishUpdate(active, installedFolder)
            return
        }
        if (active.totalBytes != null && active.bytes != active.totalBytes) {
            throw RequestError("409 Conflict", "The folder transfer is incomplete.")
        }
        if (active.files.isEmpty()) {
            throw RequestError("409 Conflict", "Upload at least one file before finishing.")
        }
        synchronized(lock) {
            if (closed) throw IOException("The receiver was closed")
            val destination = File(libraryDirectory, UUID.randomUUID().toString())
            try {
                active.files.forEach { (path, file) -> active.baseline.setProperty(path, file.digest) }
                writeProperties(File(active.staging, manifestName), active.baseline)
                Files.move(active.staging.toPath(), destination.toPath())
            } catch (_: IOException) {
                throw RequestError("500 Internal Server Error", "Gameplay could not save the game folder.")
            }
            synchronized(stagingDirectories) { stagingDirectories.remove(active.staging) }
            transfer = null
            completed = File(destination, active.folder.name)
        }
    }

    private fun checkFile(relativePath: String, headers: Map<String, String>): Boolean {
        val folder = installedFolder ?: throw RequestError("409 Conflict", "This receiver is importing a new game.")
        val active = requireTransfer()
        val count = checkNotNull(active.expectedFiles)
        validatePath(relativePath)
        val digest = headers["x-file-sha256"]?.takeIf(digestPattern::matches)
            ?: throw RequestError("400 Bad Request", "Invalid file digest.")
        val length = headers["x-file-bytes"]?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw RequestError("400 Bad Request", "Invalid file size.")
        active.plans[relativePath]?.let {
            if (it.digest != digest || it.length != length) {
                throw RequestError("409 Conflict", "The file changed during the update.")
            }
            return it.upload
        }
        if (active.plans.size >= count) {
            throw RequestError("409 Conflict", "The update contains more files than expected.")
        }
        val original = try {
            installedDigest(folder, relativePath)
        } catch (_: IOException) {
            throw RequestError("500 Internal Server Error", "Gameplay could not check the installed file.")
        }
        val baseline = active.baseline.getProperty(relativePath)
        val upload = original != digest && baseline != digest
        if (upload && original != baseline) throw localConflict(relativePath)
        active.plans[relativePath] = UpdateFile(length, digest, original, upload)
        return upload
    }

    private fun finishUpdate(active: Transfer, folder: File) = synchronized(lock) {
        if (closed) throw IOException("The receiver was closed")
        val changes = active.plans.filterValues { it.upload }
        if (active.plans.size != active.expectedFiles || changes.keys != active.files.keys) {
            throw RequestError("409 Conflict", "The folder update is incomplete.")
        }
        synchronized(updateLock) {
            val backup = try {
                recoverUpdates(libraryDirectory)
                Files.createTempDirectory(libraryDirectory.toPath(), recoveryPrefix).toFile()
            } catch (_: IOException) {
                throw RequestError("500 Internal Server Error", "Gameplay could not prepare a safe update backup.")
            }
            try {
                val manifest = loadManifest(folder)
                val journal = Properties().apply {
                    setProperty("folder", folder.relativeTo(libraryDirectory).invariantSeparatorsPath)
                }
                val directories = linkedSetOf<File>()
                active.plans.forEach { (path, plan) ->
                    if (manifest.getProperty(path) != active.baseline.getProperty(path)) throw localConflict(path)
                    val tracked = active.baseline.containsKey(path)
                    if (plan.upload || plan.original == plan.digest && (tracked || active.baseline.isEmpty())) {
                        manifest.setProperty(path, plan.digest)
                    }
                }
                changes.entries.forEachIndexed { index, (path, plan) ->
                    if (installedDigest(folder, path) != plan.original) throw localConflict(path)
                    val target = File(folder, path)
                    val missing = generateSequence(target.parentFile) { it.parentFile }
                        .takeWhile { !it.exists() }.toList()
                    directories.addAll(missing.asReversed())
                    journal.setProperty("path.$index", path)
                    journal.setProperty("new.$index", plan.digest)
                    plan.original?.let { original ->
                        journal.setProperty("old.$index", original)
                        Files.copy(target.toPath(), File(backup, index.toString()).toPath(), NOFOLLOW_LINKS)
                    }
                }
                journal.setProperty("files", changes.size.toString())
                journal.setProperty("directories", directories.size.toString())
                directories.forEachIndexed { index, directory ->
                    journal.setProperty("directory.$index", directory.relativeTo(folder).invariantSeparatorsPath)
                }
                writeProperties(File(backup, manifestName), manifest)
                val pendingJournal = File(backup, "pending")
                writeProperties(pendingJournal, journal)
                moveAtomically(pendingJournal, File(backup, journalName))
                // The manifest move commits the update. While it remains here, a restart rolls back.
                directories.forEach { Files.createDirectory(it.toPath()) }
                changes.keys.forEach { path -> moveAtomically(File(active.folder, path), File(folder, path)) }
                moveAtomically(File(backup, manifestName), File(folder.parentFile, manifestName))
            } catch (error: IOException) {
                clearTransfer()
                throw RequestError("500 Internal Server Error", "Gameplay could not apply the folder update. Restart the update.")
            } finally {
                try {
                    recoverUpdate(libraryDirectory, backup)
                } catch (_: IOException) {
                    throw RequestError("500 Internal Server Error", "Gameplay could not restore the interrupted update.")
                }
            }
        }
        clearTransfer()
        completed = folder
    }

    private fun loadManifest(folder: File): Properties {
        val source = File(folder.parentFile, manifestName)
        return if (Files.exists(source.toPath(), NOFOLLOW_LINKS)) readProperties(source) else Properties()
    }

    private fun requireTransfer(): Transfer = transfer
        ?: throw RequestError("409 Conflict", "Start a folder transfer first.")

    private fun clearTransfer() {
        transfer?.staging?.let(::discard)
        transfer = null
    }

    private fun discard(staging: File) {
        synchronized(stagingDirectories) {
            runCatching { staging.toPath().deleteRecursively() }
            stagingDirectories.remove(staging)
        }
    }

    private fun decode(encoded: String): String = try {
        URLDecoder.decode(encoded, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw RequestError("400 Bad Request", "The file or folder name is incorrectly encoded.")
    }

    private fun readRequest(input: BufferedInputStream): Request {
        val deadline = System.nanoTime() + 5_000_000_000L
        val parts = readLine(input, deadline).split(' ')
        if (parts.size != 3 || !parts[1].startsWith('/') || parts[2] != "HTTP/1.1") {
            throw RequestError("400 Bad Request", "Invalid HTTP request.")
        }

        val headers = mutableMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input, deadline)
            if (line.isEmpty()) break
            total += line.length
            if (total > 16_384) throw RequestError("431 Request Header Fields Too Large", "Request headers are too large.")
            val separator = line.indexOf(':')
            if (separator <= 0) throw RequestError("400 Bad Request", "Invalid HTTP header.")
            val name = line.substring(0, separator).lowercase(Locale.ROOT)
            if (headers.put(name, line.substring(separator + 1).trim()) != null) {
                throw RequestError("400 Bad Request", "Duplicate HTTP header.")
            }
        }
        return Request(parts[0], parts[1], headers)
    }

    private fun readLine(input: InputStream, deadline: Long): String {
        val line = StringBuilder()
        while (line.length < 16_384 && System.nanoTime() - deadline < 0) {
            when (val value = input.read()) {
                -1 -> throw EOFException()
                13 -> if (input.read() == 10) return line.toString() else break
                9, in 32..126 -> line.append(value.toChar())
                else -> break
            }
        }
        throw RequestError("400 Bad Request", "Invalid HTTP request line or header.")
    }

    private fun respond(
        output: OutputStream,
        status: String,
        body: ByteArray = ByteArray(0),
        type: String = "text/plain; charset=utf-8",
    ) {
        val lengthHeader = if (status == "204 No Content") "" else "Content-Length: ${body.size}\r\n"
        output.write(
            ("HTTP/1.1 $status\r\nConnection: close\r\n$lengthHeader" +
                "Content-Type: $type\r\nCache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; " +
                "style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'\r\n\r\n")
                .toByteArray(Charsets.US_ASCII),
        )
        output.write(body)
        output.flush()
    }

    private data class Request(val method: String, val target: String, val headers: Map<String, String>)
    private class Transfer(
        val id: String,
        val staging: File,
        val folder: File,
        val totalBytes: Long?,
        val expectedFiles: Int?,
        val baseline: Properties,
    ) {
        var bytes = 0L
        val files = mutableMapOf<String, UploadedFile>()
        val plans = mutableMapOf<String, UpdateFile>()
    }
    private data class UploadedFile(val length: Long, val digest: String)
    private data class UpdateFile(val length: Long, val digest: String, val original: String?, val upload: Boolean)
    private class RequestError(val status: String, override val message: String) : Exception(message)

    companion object {
        /** Roll back any update that stopped before publishing its new manifest. Call on an IO thread. */
        fun recoverUpdates(libraryDirectory: File) {
            synchronized(updateLock) {
                val library = libraryDirectory.canonicalFile
                library.listFiles()?.filter { it.name.startsWith(recoveryPrefix) }?.forEach { backup ->
                    recoverUpdate(library, backup)
                }
            }
        }

        private fun recoverUpdate(library: File, backup: File) {
            val journalFile = File(backup, journalName)
            if (!Files.isDirectory(backup.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid update backup")
            if (journalFile.isFile && File(backup, manifestName).isFile) {
                try {
                    val journal = readProperties(journalFile)
                    val folderPath = journal.getProperty("folder")
                    validatePath(folderPath)
                    val folder = File(library, folderPath)
                    if (folder.parentFile?.parentFile != library || folder.canonicalFile != folder ||
                        !Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)
                    ) throw IOException("Invalid update recovery folder")
                    val fileCount = journal.getProperty("files").toInt()
                    for (index in (0 until fileCount).reversed()) {
                        val path = journal.getProperty("path.$index")
                        validatePath(path)
                        val original = journal.getProperty("old.$index")
                        val current = installedDigest(folder, path)
                        if (current == original) continue
                        if (current != journal.getProperty("new.$index")) throw IOException("An update file was edited locally")
                        val target = File(folder, path)
                        if (original == null) {
                            Files.delete(target.toPath())
                        } else {
                            val saved = File(backup, index.toString())
                            val restoring = File(backup, "restoring")
                            Files.copy(saved.toPath(), restoring.toPath(), NOFOLLOW_LINKS, REPLACE_EXISTING)
                            moveAtomically(restoring, target)
                        }
                    }
                    val directoryCount = journal.getProperty("directories").toInt()
                    for (index in (0 until directoryCount).reversed()) {
                        val path = journal.getProperty("directory.$index")
                        validatePath(path)
                        val directory = File(folder, path)
                        if (directory == directory.canonicalFile && Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) {
                            directory.delete()
                        }
                    }
                } catch (error: RuntimeException) {
                    throw IOException("Invalid update recovery journal", error)
                } catch (error: RequestError) {
                    throw IOException("Unsafe update recovery path", error)
                }
            }
            // If cleanup is interrupted, removing the marker first makes repeated recovery safe.
            Files.deleteIfExists(journalFile.toPath())
            runCatching { backup.toPath().deleteRecursively() }
        }

        private fun readProperties(file: File): Properties = Properties().apply {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Invalid update metadata")
            try {
                file.inputStream().use(::load)
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid update metadata", error)
            }
        }

        private fun writeProperties(file: File, properties: Properties) {
            FileOutputStream(file).use {
                properties.store(it, null)
                it.fd.sync()
            }
        }

        private fun moveAtomically(source: File, target: File) {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }

        private fun installedDigest(folder: File, relativePath: String): String? {
            if (folder != folder.canonicalFile || !Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS)) {
                throw localConflict(relativePath)
            }
            val target = File(folder, relativePath)
            var parent = target.parentFile
            while (parent != folder) {
                if (Files.exists(parent.toPath(), NOFOLLOW_LINKS) &&
                    !Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)
                ) throw localConflict(relativePath)
                parent = parent.parentFile
            }
            if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) return null
            if (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) throw localConflict(relativePath)
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }

        private fun validatePath(relativePath: String) {
            val segments = relativePath.split('/')
            if (segments.size > 64 || relativePath.length > 4096) {
                throw RequestError("400 Bad Request", "The file path is too long.")
            }
            segments.forEach(::validateSegment)
        }

        private fun localConflict(path: String) = RequestError(
            "409 Conflict",
            "The installed file at ${path.take(240)} has local changes or is untracked. The update was not applied.",
        )

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            for (byte in bytes) {
                append(hexDigits[(byte.toInt() and 0xff) ushr 4])
                append(hexDigits[byte.toInt() and 0xf])
            }
        }

        private fun validateSegment(segment: String) {
            if (segment.isBlank() || segment == "." || segment == ".." ||
                segment.toByteArray(Charsets.UTF_8).size > 255 ||
                segment.any { it == '/' || it == '\\' || it == ':' || Character.isISOControl(it) }
            ) {
                throw RequestError("400 Bad Request", "The file or folder name is invalid.")
            }
        }


        private val stagingDirectories = mutableSetOf<File>()
        private val updateLock = Any()
        private val transferIdPattern = Regex("[a-f0-9]{32}")
        private val digestPattern = Regex("[a-f0-9]{64}")
        private const val journalName = "journal"
        private const val recoveryPrefix = ".update-recovery-"
        private const val manifestName = ".wifi-import-sha256"
        private const val hexDigits = "0123456789abcdef"
    }
}
