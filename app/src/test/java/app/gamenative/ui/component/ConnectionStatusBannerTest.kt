package app.gamenative.ui.component

import app.gamenative.ui.enums.ConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusBannerTest {
    @Test
    fun `banner remains visible for every non-connected state`() {
        assertFalse(shouldShowConnectionBanner(ConnectionState.CONNECTED))
        assertTrue(shouldShowConnectionBanner(ConnectionState.CONNECTING))
        assertTrue(shouldShowConnectionBanner(ConnectionState.DISCONNECTED))
        assertTrue(shouldShowConnectionBanner(ConnectionState.OFFLINE_MODE))
        assertTrue(shouldShowConnectionBanner(ConnectionState.LOGGED_OUT))
    }
}
