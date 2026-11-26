package app.gamenative.itch

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.service.GameDownloadQueue
import app.gamenative.service.NotificationHelper
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Outcome of asking the download manager to start an itch.io install/update. */
sealed interface ItchDownloadStart {
    data class Started(val downloadInfo: DownloadInfo, val upload: ItchUpload) : ItchDownloadStart
    data class AlreadyActive(val downloadInfo: DownloadInfo) : ItchDownloadStart
    data class NeedsUploadSelection(val selection: ItchUploadSelection) : ItchDownloadStart
    data class OpenInBrowser(val url: String) : ItchDownloadStart
    data class Failed(val message: String, val cause: Throwable? = null) : ItchDownloadStart
}

/**
 * Downloads itch.io uploads through the shared [GameDownloadQueue]/[DownloadInfo]
 * machinery and hands finished files to [ItchInstallCoordinator].
 *
 * Pausing cancels the transfer coroutine but keeps the `.part` file and its
 * validator sidecar, so resuming continues the same upload only when the server
 * still serves the same entity. Cancelling deletes the partial file.
 */
@Singleton
class ItchDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ItchLibraryRepository,
    private val auth: ItchAuthManager,
    private val api: ItchApiClient,
    private val coordinator: ItchInstallCoordinator,
    private val notifications: NotificationHelper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<Long, DownloadInfo>()
    private val uploads = ConcurrentHashMap<Long, ItchUpload>()
    private val downloader = ItchResumableDownloader(Net.httpForParallelDownloads(4))

    fun getDownloadInfo(gameId: Long): DownloadInfo? = downloads[gameId]

    fun getActiveDownloads(): Map<Long, DownloadInfo> = downloads.toMap()

    fun isDownloading(gameId: Long): Boolean = downloads[gameId]?.isActive() == true

    fun hasPartialDownload(gameId: Long): Boolean =
        partialFiles(gameId).isNotEmpty()

    /** Display name for the downloads screen; never performs I/O. */
    fun gameTitle(gameId: Long): String =
        ItchGameCache.get(gameId)?.title ?: ItchNameCache.titleFor(gameId) ?: "itch.io game"

    /** Game ids that have a resumable `.part` on disk. */
    fun partialGameIds(): List<Long> = ItchPaths.downloadsDir(context).listFiles().orEmpty()
        .filter { it.isDirectory && it.listFiles().orEmpty().any { file -> file.name.endsWith(".part") } }
        .mapNotNull { it.name.toLongOrNull() }

    fun downloadedBytes(gameId: Long): Long =
        partialFiles(gameId).sumOf { it.length() }

    /**
     * Resolves the upload to install (unless one was chosen explicitly) and starts
     * or resumes the transfer. Ambiguous uploads are reported back for the picker.
     */
    suspend fun start(gameId: Long, uploadId: Long? = null): ItchDownloadStart =
        withContext(Dispatchers.IO) {
            downloads[gameId]?.let { existing ->
                if (existing.isActive()) {
                    return@withContext ItchDownloadStart.AlreadyActive(existing)
                }
                // A paused/cancelled job may still be unwinding; let it finish so two
                // transfers can never write the same partial file.
                existing.awaitCompletion(5_000L)
                downloads.remove(gameId, existing)
            }
            val game = repository.getGame(gameId)
                ?: return@withContext ItchDownloadStart.Failed("This itch.io game is not in the library")
            if (auth.tokenOrNull().isNullOrBlank()) {
                return@withContext ItchDownloadStart.Failed(
                    "Connect an itch.io account to download this game",
                )
            }
            val selection = repository.chooseUpload(gameId).getOrElse { error ->
                return@withContext ItchDownloadStart.Failed(
                    error.message ?: "Could not load the itch.io builds",
                    error,
                )
            }
            val chosenCandidate = (selection.choice as? ItchUploadChoice.Single)?.upload
            val upload = when {
                uploadId != null -> selection.uploads.firstOrNull { it.id == uploadId }
                chosenCandidate != null -> selection.uploads.firstOrNull { it.id == chosenCandidate.id }
                else -> null
            }
            if (upload == null) {
                return@withContext when (val choice = selection.choice) {
                    is ItchUploadChoice.None -> ItchDownloadStart.OpenInBrowser(
                        game.url.ifBlank { "https://itch.io" },
                    )
                    is ItchUploadChoice.Multiple -> ItchDownloadStart.NeedsUploadSelection(selection)
                    is ItchUploadChoice.Single -> ItchDownloadStart.Failed(
                        "The selected itch.io build is no longer available",
                    )
                }
            }
            launchDownload(game, upload, selection)
        }

    private fun launchDownload(
        game: app.gamenative.data.ItchGame,
        upload: ItchUpload,
        selection: ItchUploadSelection,
    ): ItchDownloadStart {
        val gameId = game.gameId
        if (upload.id == 0L) {
            return ItchDownloadStart.Failed("itch.io returned a build without an id")
        }
        val archive = archiveFor(gameId, upload)
        val targetDir = archive.parentFile ?: ItchPaths.partialDownloadsDir(context, gameId)
        targetDir.mkdirs()

        val info = DownloadInfo(
            jobCount = 1,
            gameId = gameId.toInt(),
            downloadingAppIds = CopyOnWriteArrayList(listOf(gameId.toInt())),
        )
        val partLength = File(archive.absolutePath + ".part").takeIf { it.isFile }?.length() ?: 0L
        info.setPersistencePath(targetDir.absolutePath)
        info.initializeBytesDownloaded(partLength)
        info.setTotalExpectedBytes(upload.size)
        info.setActive(true)
        downloads[gameId] = info
        uploads[gameId] = upload
        ItchNameCache.put(gameId, game.title)
        scope.launch { repository.recordSelectedUpload(gameId, upload) }

        // Registration must precede the coroutine launch so a second download can
        // pause this one before the first progress callback arrives.
        GameDownloadQueue.registerDownload(GameSource.ITCH, gameId.toString(), info) {
            scope.launch { start(gameId, uploadId = uploads[gameId]?.id) }
        }
        runCatching {
            notifications.trackDownload(info, game.title, NotificationHelper.NOTIFICATION_ID_ITCH)
        }
        PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameId.toInt(), true))

        var reported = partLength
        val job: Job = scope.launch {
            try {
                val token = auth.tokenOrNull()
                    ?: throw ItchDownloadException(
                        "Connect an itch.io account to download this game",
                        ItchDownloadException.Kind.ACCESS_DENIED,
                    )
                val result = downloader.download(
                    target = archive,
                    expectedUploadId = upload.id,
                    expectedSize = upload.size,
                    progress = { downloaded, total ->
                        if (downloaded < reported) {
                            // The downloader restarted from zero (different entity or
                            // the server ignored Range); restart the counters too.
                            reported = 0L
                            info.initializeBytesDownloaded(0L)
                        }
                        if (total > 0L) info.setTotalExpectedBytes(total)
                        if (downloaded > reported) {
                            info.updateBytesDownloaded(downloaded - reported)
                        }
                        reported = downloaded
                        if (total > 0L) info.setProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        info.emitProgressChange()
                    },
                ) {
                    ItchDownloadSource(
                        url = api.requestDownloadUrl(token, upload.id),
                        uploadId = upload.id,
                    )
                }
                Timber.tag(TAG).i(
                    "itch.io download finished: game=$gameId upload=${upload.id} " +
                        "bytes=${result.bytes} resumedFrom=${result.resumedFrom}",
                )
                val installResult = if (isArchive(upload)) {
                    coordinator.installZip(gameId, upload, archive)
                } else {
                    coordinator.prepareInstaller(game, upload, archive)
                }
                installResult.onFailure { error ->
                    info.updateStatusMessage("Failed: ${error.message ?: "install failed"}")
                    repository.recordError(gameId, error.message)
                }
            } catch (cancelled: CancellationException) {
                // Pause/cancel: the partial file and its validators stay for a resume.
                info.updateStatusMessage("Paused")
                throw cancelled
            } catch (error: Exception) {
                Timber.tag(TAG).e(error, "itch.io download failed for game $gameId")
                info.updateStatusMessage("Failed: ${error.message ?: "download failed"}")
                repository.recordError(gameId, error.message)
            } finally {
                GameDownloadQueue.finishDownload(GameSource.ITCH, gameId.toString(), info)
                downloads.remove(gameId, info)
                uploads.remove(gameId)
                PluviaApp.events.emitJava(AndroidEvent.DownloadStatusChanged(gameId.toInt(), false))
                PluviaApp.events.emitJava(
                    AndroidEvent.LibraryInstallStatusChanged(gameId.toInt(), GameSource.ITCH),
                )
            }
        }
        info.setDownloadJob(job)
        return ItchDownloadStart.Started(info, upload)
    }

    /** Pauses (keeps the partial file) or cancels (deletes it) the transfer. */
    fun stop(gameId: Long, deletePartial: Boolean = false) {
        val info = downloads[gameId] ?: return
        info.cancel(if (deletePartial) "Cancelled by user" else "Paused")
        if (deletePartial) {
            scope.launch {
                info.awaitCompletion(10_000L)
                withContext(Dispatchers.IO) {
                    archiveForExisting(gameId).forEach { archive ->
                        File(archive.absolutePath + ".part").delete()
                        File(archive.absolutePath + ".part.json").delete()
                        if (archive.isFile) archive.delete()
                    }
                    coordinator.existingLayout(gameId)?.stagingRoot?.listFiles()
                        ?.forEach { it.deleteRecursively() }
                }
                PluviaApp.events.emitJava(
                    AndroidEvent.DownloadStatusChanged(gameId.toInt(), false),
                )
            }
        }
    }

    private fun partialFiles(gameId: Long): List<File> {
        val dir = ItchPaths.partialDownloadsDir(context, gameId)
        return dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".part") }
    }

    private fun archiveForExisting(gameId: Long): List<File> {
        val dir = ItchPaths.partialDownloadsDir(context, gameId)
        return dir.listFiles().orEmpty().filter { file ->
            file.isFile && !file.name.endsWith(".part") && !file.name.endsWith(".json")
        }
    }

    private fun archiveFor(gameId: Long, upload: ItchUpload): File {
        val base = ItchPaths.archiveFile(context, gameId, upload.id, upload.build?.id)
        return File(base.parentFile, base.name + extensionFor(upload))
    }

    private fun extensionFor(upload: ItchUpload): String {
        val extension = upload.filename.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "zip", "exe", "msi" -> ".$extension"
            else -> ".zip"
        }
    }

    private fun isArchive(upload: ItchUpload): Boolean =
        upload.filename.lowercase().endsWith(".zip") || extensionFor(upload) == ".zip"

    companion object {
        private const val TAG = "ItchDownloadManager"
    }
}
