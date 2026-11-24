package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.DownloadInfo
import app.gamenative.enums.Marker
import app.gamenative.utils.MarkerUtils
import app.gamenative.utils.redactUrlForLogging
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/** Handles native downloading of Amazon games. */
@Singleton
class AmazonDownloadManager @Inject constructor(
    private val amazonManager: AmazonManager,
) {
    private val updateLocks = ConcurrentHashMap<String, Mutex>()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
        .build()

    companion object {
        private const val MAX_PARALLEL_DOWNLOADS = 6
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val PROGRESS_EMIT_INTERVAL = 512 * 1024L // Emit UI progress every 512 KB
        private const val TAG = "Amazon"
    }

    /** Download and install an Amazon game. */
    suspend fun downloadGame(
        context: Context,
        game: AmazonGame,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        updateLocks.computeIfAbsent(game.productId) { Mutex() }.withLock {
            recoverUpdateLocked(context, game)
            val current = amazonManager.getGameById(game.productId) ?: game
            downloadGameLocked(context, current, installPath, downloadInfo)
        }
    }

    private suspend fun downloadGameLocked(
        context: Context,
        game: AmazonGame,
        installPath: String,
        downloadInfo: DownloadInfo,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val productId = game.productId
        var update: AmazonFileUpdate? = null
        try {
            Timber.tag(TAG).i("Starting download for ${game.title} → $installPath")

            File(installPath).mkdirs()
            MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)

            // Helper to cleanup marker on early failure
            fun cleanupOnFailure() {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            }

            // ── 1. Credentials ───────────────────────────────────────────────
            if (game.entitlementId.isBlank()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Game '${game.title}' has no entitlement ID — re-sync library first"))
            }
            val bearerToken = amazonManager.getBearerToken()
            if (bearerToken == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("No Amazon credentials stored"))
            }

            // ── 2. Download spec ─────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching download information…")
            val spec = AmazonApiClient.fetchGameDownload(game.entitlementId, bearerToken)
            if (spec == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to fetch download spec from Amazon"))
            }

            Timber.tag(TAG).d("Download spec: url=${redactUrlForLogging(spec.downloadUrl)}, version=${spec.versionId}")

            // ── 3. Manifest ──────────────────────────────────────────────────
            downloadInfo.updateStatusMessage("Fetching manifest…")
            val manifestUrl = appendPath(spec.downloadUrl, "manifest.proto")
            Timber.tag(TAG).d("Manifest URL: ${redactUrlForLogging(manifestUrl)}")
            val manifestBytes = fetchBytes(manifestUrl)
            if (manifestBytes == null) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to download manifest.proto"))
            }

            val manifest = try {
                AmazonManifest.parse(manifestBytes)
            } catch (e: Exception) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Failed to parse manifest: ${e.message}", e))
            }

            val files = manifest.allFiles
            if (files.isEmpty()) {
                cleanupOnFailure()
                return@withContext Result.failure(Exception("Manifest contains no files"))
            }

            Timber.tag(TAG).i(
                "Manifest: ${files.size} file(s), ${manifest.totalInstallSize / 1_000_000} MB total"
            )

            // ── 4. Progress setup ────────────────────────────────────────────
            downloadInfo.setTotalExpectedBytes(manifest.totalInstallSize)
            downloadInfo.setProgress(0f)
            downloadInfo.emitProgressChange()

            // ── 5. Download files in parallel batches ────────────────────────
            val installDir = File(installPath)
            val baseUrl = spec.downloadUrl
            val versionChanged = (game.isInstalled || game.versionId.isNotBlank()) && game.versionId != spec.versionId
            val stagedUpdate = if (game.isInstalled) AmazonFileUpdate(installDir) else null
            update = stagedUpdate
            var completedFiles = 0
            val totalFiles = files.size

            for (batch in files.chunked(MAX_PARALLEL_DOWNLOADS)) {
                if (!downloadInfo.isActive()) {
                    Timber.tag(TAG).w("Download cancelled by user")
                    throw CancellationException("Download cancelled")
                }

                val results = batch.map { file ->
                    async {
                        downloadFileWithRetry(
                            baseUrl = baseUrl,
                            file = file,
                            installDir = installDir,
                            downloadInfo = downloadInfo,
                            versionChanged = versionChanged,
                            update = stagedUpdate,
                        )
                    }
                }.awaitAll()

                val failure = results.firstOrNull { it.isFailure }
                if (failure != null) {
                    MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                    return@withContext Result.failure(
                        failure.exceptionOrNull() ?: Exception("File download failed")
                    )
                }

                completedFiles += batch.size
                downloadInfo.updateStatusMessage("Downloading ($completedFiles/$totalFiles files)…")
                downloadInfo.emitProgressChange()
                downloadInfo.persistProgressSnapshot()
            }

            ensureActive()
            if (!downloadInfo.isActive()) throw CancellationException("Download cancelled")

            if (stagedUpdate != null) {
                withContext(NonCancellable) {
                    stagedUpdate.commit(
                        version = spec.versionId,
                        size = manifest.totalInstallSize,
                        manifest = manifestBytes,
                        markInstalled = { version, size -> amazonManager.markInstalled(productId, installPath, size, version) },
                        finish = { path -> finishUpdate(context, productId, installPath, path) },
                    )
                }
            } else {
                amazonManager.markInstalled(productId, installPath, manifest.totalInstallSize, spec.versionId)
                runCatching { cacheManifest(context, productId, manifestBytes) }.onFailure {
                    Timber.tag(TAG).w(it, "Failed to cache manifest (non-fatal)")
                }
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
            }

            downloadInfo.updateStatusMessage("Complete")
            // Clamp bytes so progress reads exactly 1.0
            val remaining = downloadInfo.getTotalExpectedBytes() - downloadInfo.getBytesDownloaded()
            if (remaining > 0L) downloadInfo.updateBytesDownloaded(remaining)
            downloadInfo.setProgress(1f)
            downloadInfo.setActive(false)
            downloadInfo.emitProgressChange()

            Timber.tag(TAG).i("Download complete for ${game.title}")
            Result.success(Unit)

        } catch (e: Exception) {
            if (e is CancellationException) {
                MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
                downloadInfo.persistProgressSnapshot()
                downloadInfo.setActive(false)
                throw e
            }
            Timber.tag(TAG).e(e, "Download failed for ${game.title}: ${e.message}")
            MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER)
            downloadInfo.updateStatusMessage("Failed: ${e.message}")
            downloadInfo.setProgress(-1f)
            downloadInfo.setActive(false)
            Result.failure(e)
        } finally {
            update?.cleanUp()
        }
    }

    internal suspend fun recoverUpdate(context: Context, game: AmazonGame) {
        withContext(Dispatchers.IO) {
            updateLocks.computeIfAbsent(game.productId) { Mutex() }.withLock {
                recoverUpdateLocked(context, game)
            }
        }
    }

    private suspend fun recoverUpdateLocked(context: Context, game: AmazonGame) {
        if (!game.isInstalled || game.installPath.isBlank()) return
        val installPath = game.installPath
        AmazonFileUpdate.recover(
            installDir = File(installPath),
            markInstalled = { version, size ->
                amazonManager.markInstalled(game.productId, installPath, size, version)
            },
            finish = { path -> finishUpdate(context, game.productId, installPath, path) },
        )
        check(MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER))
    }

    private fun finishUpdate(context: Context, productId: String, installPath: String, manifest: File) {
        cacheManifest(context, productId, manifest.readBytes())
        check(MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER))
        check(MarkerUtils.removeMarker(installPath, Marker.DOWNLOAD_IN_PROGRESS_MARKER))
    }

    private fun cacheManifest(context: Context, productId: String, bytes: ByteArray) {
        val directory = File(context.filesDir, "manifests/amazon").toPath()
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".manifest-", ".proto")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, directory.resolve("$productId.proto"), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private suspend fun downloadFileWithRetry(
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
        versionChanged: Boolean,
        update: AmazonFileUpdate?,
    ): Result<Unit> {
        var lastException: Exception? = null
        repeat(MAX_RETRIES) { attempt ->
            if (!downloadInfo.isActive()) {
                throw CancellationException("Download cancelled")
            }

            val result = downloadFile(baseUrl, file, installDir, downloadInfo, versionChanged, update)
            if (result.isSuccess) return result

            lastException = result.exceptionOrNull() as? Exception
            if (attempt < MAX_RETRIES - 1) {
                val backoffMs = RETRY_DELAY_MS * (1 shl attempt) // 1s, 2s, 4s
                Timber.tag(TAG).w(
                    "File ${file.unixPath} failed (attempt ${attempt + 1}/$MAX_RETRIES): " +
                        "${lastException?.message}. Retrying in ${backoffMs}ms…"
                )
                delay(backoffMs)
            }
        }
        return Result.failure(
            lastException ?: Exception("Failed to download ${file.unixPath} after $MAX_RETRIES attempts")
        )
    }

    private suspend fun downloadFile(
        baseUrl: String,
        file: AmazonManifest.ManifestFile,
        installDir: File,
        downloadInfo: DownloadInfo,
        versionChanged: Boolean,
        update: AmazonFileUpdate?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val destFile = File(installDir, file.unixPath).canonicalFile
        val installDirCanonical = installDir.canonicalFile.toPath()

        // Security check: prevent path traversal attacks
        if (destFile.toPath() == installDirCanonical || !destFile.toPath().startsWith(installDirCanonical)) {
            Timber.tag(TAG).e("Path traversal attempt blocked: ${file.unixPath}")
            return@withContext Result.failure(SecurityException("Invalid file path"))
        }

        var temporaryFile: File? = null
        try {
            // Skip already-complete files (resume-friendly)
            if (canReuseAmazonFile(destFile, file, versionChanged)) {
                // Count recovered bytes for progress without skewing transfer speed ETA.
                downloadInfo.updateBytesDownloaded(file.size, trackSpeed = false)
                return@withContext Result.success(Unit)
            }

            val targetFile = update?.stageFile(destFile) ?: destFile
            val targetDirectory = targetFile.toPath().parent
            Files.createDirectories(targetDirectory)
            val tmpFile = Files.createTempFile(targetDirectory, ".AmazonDownload-", ".tmp").toFile()
            temporaryFile = tmpFile

            // nile uses /files/{hash_hex} per downloading/manager.py, NOT the unix path
            val hashHex = file.hashBytes.joinToString("") { "%02x".format(it) }
            val url = appendPath(baseUrl, "files/$hashHex")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "nile/0.1 Amazon")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("HTTP ${response.code} for ${file.unixPath}")
                    )
                }

                response.body.byteStream().use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var bytesSinceLastEmit = 0L
                        while (input.read(buf).also { read = it } != -1) {
                                    if (!downloadInfo.isActive()) {
                                        throw CancellationException("Download cancelled")
                                    }
                            output.write(buf, 0, read)
                            downloadInfo.updateBytesDownloaded(read.toLong())
                            bytesSinceLastEmit += read
                            // Emit progress every ~512 KB so UI updates smoothly during large files
                            if (bytesSinceLastEmit >= PROGRESS_EMIT_INTERVAL) {
                                bytesSinceLastEmit = 0L
                                downloadInfo.emitProgressChange()
                                downloadInfo.persistProgressSnapshot()
                            }
                        }
                    }
                }
            }

            check(tmpFile.length() == file.size) { "Size mismatch for ${file.unixPath}" }
            check(!file.hasSha256 || file.matchesSha256(tmpFile)) { "SHA-256 mismatch for ${file.unixPath}" }
            Files.move(tmpFile.toPath(), targetFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error downloading ${file.unixPath}")
            Result.failure(e)
        } finally {
            temporaryFile?.delete()
        }
    }

    /** Append [segment] to [baseUrl] path before any query string. */
    private fun appendPath(baseUrl: String, segment: String): String {
        val qIdx = baseUrl.indexOf('?')
        return if (qIdx == -1) {
            "$baseUrl/$segment"
        } else {
            val path = baseUrl.substring(0, qIdx)
            val query = baseUrl.substring(qIdx)
            "$path/$segment$query"
        }
    }

    /** Download raw bytes from [url], returning null on error. */
    private fun fetchBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.bytes()
        }
    } catch (e: Exception) {
        Timber.tag(TAG).e(e, "fetchBytes failed for ${redactUrlForLogging(url)}")
        null
    }
}

internal class AmazonFileUpdate private constructor(
    private val installRoot: File,
    private val workRoot: File,
) {
    constructor(installDir: File) : this(
        installDir.canonicalFile,
        createWorkRoot(installDir.canonicalFile),
    )

    private val staged = File(workRoot, "staged")
    private val backups = File(workRoot, "backups")
    private val journal = File(workRoot, "journal")
    private val ready = File(workRoot, "ready")
    private val manifestFile = File(workRoot, "manifest.proto")

    fun stageFile(destination: File): File = File(staged, destination.relativeTo(installRoot).path)

    suspend fun commit(
        version: String,
        size: Long,
        manifest: ByteArray,
        markInstalled: suspend (String, Long) -> Unit,
        finish: (File) -> Unit,
    ) {
        val entries = staged.walkTopDown().onFail { _, error -> throw error }.filter(File::isFile).map { file ->
            val relative = file.relativeTo(staged).path
            val destination = destination(relative)
            check(!destination.exists() || destination.isFile) { "Update destination is not a file: $relative" }
            Entry(relative, destination.exists())
        }.toList()
        manifestFile.writeBytes(manifest)
        writePlan(Plan(version, size, entries))
        try {
            for (entry in entries) {
                val target = destination(entry.path).toPath()
                Files.createDirectories(target.parent)
                if (entry.hadFile) {
                    val backup = File(backups, entry.path).toPath()
                    Files.createDirectories(backup.parent)
                    Files.move(target, backup, ATOMIC_MOVE)
                }
                Files.move(File(staged, entry.path).toPath(), target, ATOMIC_MOVE, REPLACE_EXISTING)
            }
            // Once ready exists recovery can safely publish metadata, even if Room has already
            // committed it. Before then recovery restores the previous files.
            Files.createFile(ready.toPath())
            markInstalled(version, size)
        } catch (failure: Exception) {
            try {
                Files.deleteIfExists(ready.toPath())
                rollBack(entries)
                discard()
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
        finish(manifestFile)
        discard()
    }

    private fun rollBack(entries: List<Entry>) {
        for (entry in entries.asReversed()) {
            val target = destination(entry.path).toPath()
            val backup = File(backups, entry.path)
            if (entry.hadFile && backup.exists()) {
                Files.move(backup.toPath(), target, ATOMIC_MOVE, REPLACE_EXISTING)
            } else if (!entry.hadFile && !File(staged, entry.path).exists()) {
                Files.deleteIfExists(target)
            }
        }
    }

    private fun destination(path: String): File {
        val destination = File(installRoot, path)
        check(destination.canonicalFile == destination && destination.toPath().startsWith(installRoot.toPath()) &&
            destination != installRoot) { "Invalid update destination: $path" }
        return destination
    }

    private data class Entry(val path: String, val hadFile: Boolean)
    private data class Plan(val version: String, val size: Long, val entries: List<Entry>)

    private fun writePlan(plan: Plan) {
        check(plan.entries.size <= MAX_PLAN_ENTRIES) { "Amazon update journal has too many entries" }
        val temporary = File(workRoot, "journal.tmp")
        DataOutputStream(temporary.outputStream().buffered()).use { stream ->
            stream.writeUTF(plan.version)
            stream.writeLong(plan.size)
            stream.writeInt(plan.entries.size)
            plan.entries.forEach { entry ->
                stream.writeUTF(entry.path)
                stream.writeBoolean(entry.hadFile)
            }
        }
        Files.move(temporary.toPath(), journal.toPath(), ATOMIC_MOVE)
    }

    private fun readPlan(): Plan = DataInputStream(journal.inputStream().buffered()).use { stream ->
        val version = stream.readUTF()
        val size = stream.readLong()
        val count = stream.readInt()
        check(count in 0..MAX_PLAN_ENTRIES && count <= (journal.length() - 14) / 4) {
            "Invalid Amazon update journal entry count: $count"
        }
        val entries = List(count) { Entry(stream.readUTF(), stream.readBoolean()) }
        Plan(version, size, entries)
    }

    private fun discard() {
        val completed = File(installRoot, DONE_PREFIX + workRoot.name.removePrefix(WORK_PREFIX))
        Files.move(workRoot.toPath(), completed.toPath(), ATOMIC_MOVE)
        reclaimCompleted(completed)
    }

    fun cleanUp() {
        // No filesystem changes can have occurred before the journal was published.
        if (workRoot.isDirectory && !journal.exists()) discard()
    }

    companion object {
        private const val WORK_PREFIX = ".GameNativeAmazonUpdate-v1-"
        private const val DONE_PREFIX = ".GameNativeAmazonUpdateDone-v1-"
        private const val OWNER_FILE = "gamenative-owner"
        private const val MAX_PLAN_ENTRIES = 1_000_000

        private fun ownerSignature(id: String) = "GameNative Amazon update v1 $id"

        private fun createWorkRoot(installRoot: File): File {
            val id = UUID.randomUUID().toString()
            val root = File(installRoot, WORK_PREFIX + id)
            Files.createDirectory(root.toPath())
            File(root, OWNER_FILE).writeText(ownerSignature(id))
            return root
        }

        private fun owns(directory: File, prefix: String): Boolean {
            if (!directory.name.startsWith(prefix)) return false
            val id = directory.name.removePrefix(prefix)
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) return false
            val owner = File(directory, OWNER_FILE)
            val signature = ownerSignature(id)
            return owner.isFile && owner.canonicalFile == owner && owner.length() == signature.length.toLong() &&
                owner.readText() == signature
        }

        private fun reclaimCompleted(directory: File) {
            val children = directory.listFiles()?.filter { it.name != OWNER_FILE } ?: return
            if (children.all(File::deleteRecursively)) {
                File(directory, OWNER_FILE).delete()
                directory.delete()
            }
        }

        suspend fun recover(
            installDir: File,
            markInstalled: suspend (String, Long) -> Unit,
            finish: (File) -> Unit,
        ) {
            val installRoot = installDir.canonicalFile
            val directories = checkNotNull(installRoot.listFiles()) {
                "Amazon game install directory is unavailable: $installRoot"
            }
            for (directory in directories) {
                if (!directory.name.startsWith(WORK_PREFIX) && !directory.name.startsWith(DONE_PREFIX)) continue
                if (!directory.isDirectory || directory.canonicalFile != directory) continue
                if (owns(directory, DONE_PREFIX)) {
                    reclaimCompleted(directory)
                } else if (owns(directory, WORK_PREFIX)) {
                    val update = AmazonFileUpdate(installRoot, directory)
                    if (update.journal.exists()) {
                        val plan = update.readPlan()
                        if (update.ready.exists()) {
                            markInstalled(plan.version, plan.size)
                            finish(update.manifestFile)
                        } else {
                            update.rollBack(plan.entries)
                        }
                    }
                    update.discard()
                }
            }
        }
    }
}

internal fun canReuseAmazonFile(
    destination: File,
    manifestFile: AmazonManifest.ManifestFile,
    versionChanged: Boolean,
): Boolean {
    if (!destination.isFile || destination.length() != manifestFile.size) return false
    return if (manifestFile.hasSha256) manifestFile.matchesSha256(destination) else !versionChanged
}

private val AmazonManifest.ManifestFile.hasSha256: Boolean
    get() = hashAlgorithm == 0 && hashBytes.isNotEmpty()

private fun AmazonManifest.ManifestFile.matchesSha256(file: File): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().contentEquals(hashBytes)
}
