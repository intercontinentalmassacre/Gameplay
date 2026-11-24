package app.gamenative.service

import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import timber.log.Timber

/** Coordinates downloads from every store so only one transfer is active at a time. */
object GameDownloadQueue {
    data class DownloadEntry(
        val gameSource: GameSource,
        val gameId: String,
        val downloadInfo: DownloadInfo,
        val resume: () -> Unit,
    )

    private val lock = Any()
    private val downloads = LinkedHashMap<String, DownloadEntry>()

    private fun key(source: GameSource, gameId: String) = "${source.name}_$gameId"

    fun registerDownload(
        gameSource: GameSource,
        gameId: String,
        downloadInfo: DownloadInfo,
        resume: () -> Unit,
    ) {
        val downloadKey = key(gameSource, gameId)
        val toPause = synchronized(lock) {
            downloadInfo.setQueueIdentifiers(gameSource, gameId)
            downloads.remove(downloadKey)
            downloads[downloadKey] = DownloadEntry(gameSource, gameId, downloadInfo, resume)
            downloads.values.filter { it.downloadInfo !== downloadInfo && it.downloadInfo.isActive() }
        }
        toPause.forEach {
            Timber.i("[DownloadQueue] Pausing ${it.gameSource}:${it.gameId}")
            it.downloadInfo.pause("Paused while another download runs", autoPaused = true)
        }
    }

    /** Called from a download coroutine's finally block. Auto-paused entries remain queued. */
    fun finishDownload(gameSource: GameSource, gameId: String, downloadInfo: DownloadInfo) {
        if (downloadInfo.wasAutoPaused()) return
        unregisterDownload(gameSource, gameId, downloadInfo)
    }

    fun unregisterDownload(gameSource: GameSource, gameId: String, downloadInfo: DownloadInfo) {
        val next = synchronized(lock) {
            val downloadKey = key(gameSource, gameId)
            if (downloads[downloadKey]?.downloadInfo !== downloadInfo) return
            downloads.remove(downloadKey)
            downloads.values.firstOrNull { it.downloadInfo.consumeAutoPaused() }
        }
        next?.let {
            Timber.i("[DownloadQueue] Resuming ${it.gameSource}:${it.gameId}")
            it.resume()
        }
    }

    /** Remove every current entry before cancelling jobs, so none can auto-resume another. */
    fun drainForPause(): List<DownloadEntry> = synchronized(lock) {
        downloads.values.toList().also { downloads.clear() }
    }

    fun getActiveDownloads(): Map<String, DownloadEntry> = synchronized(lock) { downloads.toMap() }
    fun getActiveDownloadCount(): Int = synchronized(lock) { downloads.values.count { it.downloadInfo.isActive() } }
    fun isDownloadRegistered(source: GameSource, gameId: String): Boolean =
        synchronized(lock) { downloads.containsKey(key(source, gameId)) }
    fun getDownloadInfo(source: GameSource, gameId: String): DownloadInfo? =
        synchronized(lock) { downloads[key(source, gameId)]?.downloadInfo }
}
