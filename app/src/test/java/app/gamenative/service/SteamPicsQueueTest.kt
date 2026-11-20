package app.gamenative.service

import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamPicsQueueTest {
    @Test
    fun `duplicate app ids coalesce during token lookup and response processing`() = runBlocking {
        val queue = SteamPicsQueue()
        val request = PICSRequest(id = 1)
        assertEquals(1, queue.enqueue(listOf(request)) { pending ->
            assertEquals(0, queue.enqueue(listOf(PICSRequest(id = 1))))
            pending.single().accessToken = 42L
        })
        assertEquals(1, queue.enqueue(listOf(PICSRequest(id = 1), PICSRequest(id = 2))))

        val first = queue.batches.first()
        assertEquals(42L, first.single().accessToken)
        assertEquals(0, queue.enqueue(listOf(PICSRequest(id = 1))))
        queue.release(first)
        assertEquals(1, queue.enqueue(listOf(PICSRequest(id = 1))))
        assertEquals(listOf(2), queue.batches.first().map { it.id })
        assertEquals(listOf(1), queue.batches.first().map { it.id })
    }

    @Test
    fun `interrupted work can be queued again on reconnect`() = runBlocking {
        val queue = SteamPicsQueue()
        try {
            queue.enqueue(listOf(PICSRequest(id = 1))) { throw CancellationException("connection closed") }
        } catch (_: CancellationException) {
            // The pending claim must be released when token lookup is interrupted.
        }
        assertEquals(1, queue.enqueue(listOf(PICSRequest(id = 1))))
        queue.discardQueued()
        assertEquals(1, queue.enqueue(listOf(PICSRequest(id = 1))))
        assertEquals(listOf(1), queue.batches.first().map { it.id })
    }
}
