package com.winlator.xserver

import android.app.Application
import com.winlator.xconnector.XInputStream
import com.winlator.xconnector.XOutputStream
import com.winlator.xconnector.XStreamLock
import com.winlator.xserver.InputDeviceManagerButtonReleaseTest.HeadlessDrawable
import com.winlator.xserver.requests.WindowRequests
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HeadlessDrawable::class])
class WindowGravityTest {
    @Test
    fun childKeepsPositionAndReportsNorthWestGravityAfterParentResizes() {
        val server = XServer(ScreenInfo(640, 480), false)
        val output = object : XOutputStream(64) {
            override fun lock(): XStreamLock = XStreamLock {}
        }
        val client = XClient(server, null, output)
        val manager = server.windowManager
        val parent = manager.createWindow(
            42, manager.rootWindow, 0, 0, 400, 300,
            WindowAttributes.WindowClass.INPUT_ONLY, null, 0, client,
        )
        val child = manager.createWindow(
            43, parent, 12, 24, 100, 80,
            WindowAttributes.WindowClass.INPUT_ONLY, null, 0, client,
        )

        manager.configureWindow(parent, Bitmask((Window.FLAG_WIDTH or Window.FLAG_HEIGHT).toLong()), input(320, 240))
        WindowRequests.getWindowAttributes(client, input(child.id), output)

        assertEquals(12, child.x.toInt())
        assertEquals(24, child.y.toInt())
        assertEquals(1, output.buffer.get(15).toInt()) // NorthWestGravity in the X11 response.
    }

    private fun input(vararg words: Int): XInputStream {
        val values = words.iterator()
        return object : XInputStream(0) {
            override fun readInt(): Int = values.nextInt()
        }
    }
}
