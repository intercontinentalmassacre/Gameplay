package app.gamenative.service

import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDownloadQueueTest {
    @Test
    fun `pausing every download does not resume an auto-paused transfer`() {
        val previous = DownloadInfo(gameId = 1, downloadingAppIds = CopyOnWriteArrayList())
        val current = DownloadInfo(gameId = 2, downloadingAppIds = CopyOnWriteArrayList())
        val currentJob = Job()
        var resumed = false

        try {
            GameDownloadQueue.registerDownload(GameSource.STEAM, "1", previous) { resumed = true }
            GameDownloadQueue.registerDownload(GameSource.EPIC, "2", current) {}
            assertTrue(previous.wasAutoPaused())

            GameDownloadQueue.drainForPause().forEach { it.downloadInfo.pause() }
            current.setDownloadJob(currentJob)
            GameDownloadQueue.finishDownload(GameSource.EPIC, "2", current)
            GameDownloadQueue.finishDownload(GameSource.STEAM, "1", previous)

            assertTrue(currentJob.isCancelled)
            assertFalse(current.isActive())
            assertFalse(previous.wasAutoPaused())
            assertFalse(resumed)
            assertTrue(GameDownloadQueue.getActiveDownloads().isEmpty())
        } finally {
            GameDownloadQueue.drainForPause()
            currentJob.cancel()
        }
    }
}
