package app.gamenative.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamKeepAliveTest {

    @Test
    fun `one session ending does not drop keepalive of another`() {
        val a = "session-a-${System.nanoTime()}"
        val b = "session-b-${System.nanoTime()}"
        try {
            SteamService.acquireKeepAlive(a)
            SteamService.acquireKeepAlive(b)
            assertTrue(SteamService.keepAlive)
            assertEquals(2, SteamService.keepAliveSessionCount())

            SteamService.releaseKeepAlive(a)
            assertTrue("B must keep the service alive", SteamService.keepAlive)

            SteamService.releaseKeepAlive(b)
            assertFalse(SteamService.keepAlive)
            assertEquals(0, SteamService.keepAliveSessionCount())
        } finally {
            SteamService.releaseKeepAlive(a)
            SteamService.releaseKeepAlive(b)
        }
    }

    @Test
    fun `duplicate acquire and repeated release are safe`() {
        val id = "session-dup-${System.nanoTime()}"
        SteamService.acquireKeepAlive(id)
        SteamService.acquireKeepAlive(id)
        assertEquals(1, SteamService.keepAliveSessionCount())

        SteamService.releaseKeepAlive(id)
        SteamService.releaseKeepAlive(id)
        assertFalse(SteamService.keepAlive)
        assertEquals(0, SteamService.keepAliveSessionCount())
    }
}
