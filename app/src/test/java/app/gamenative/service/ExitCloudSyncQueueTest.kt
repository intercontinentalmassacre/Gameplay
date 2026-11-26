package app.gamenative.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitCloudSyncQueueTest {
    @Test
    fun sameGameRelaunchWaitsForTheInFlightExitSync() = runBlocking {
        val queue = ExitCloudSyncQueue(this)
        val release = CompletableDeferred<Unit>()
        val sync = queue.start("STEAM_123") { release.await() }
        assertSame(sync, queue.start("STEAM_123") { error("Duplicate sync") })
        val sameGame = async(start = CoroutineStart.UNDISPATCHED) { queue.await("STEAM_123") }
        val otherGame = async(start = CoroutineStart.UNDISPATCHED) { queue.await("STEAM_456") }

        assertFalse(sameGame.isCompleted)
        assertTrue(otherGame.isCompleted)
        release.complete(Unit)
        sameGame.await()
        assertTrue(sync.isCompleted)
    }
}
