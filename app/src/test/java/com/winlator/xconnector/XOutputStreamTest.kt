package com.winlator.xconnector

import android.app.Application
import android.os.Looper
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [XOutputStreamTest.HeadlessClientSocket::class])
class XOutputStreamTest {
    @Test
    fun `main thread drops blocked writes after one grace period and resumes when writable`() {
        assertTrue(Looper.getMainLooper().isCurrentThread)
        val socket = RecordingSocket()
        val stream = XOutputStream(socket, 32)

        fun send(event: Byte) = stream.lock().use { stream.writeByte(event) }

        send(1)
        assertEquals(2, socket.pollCount)
        send(2)
        assertEquals(3, socket.pollCount)
        assertTrue(socket.events.isEmpty())

        socket.ready = true
        send(3)
        assertEquals(listOf<Byte>(3), socket.events)

        socket.ready = false
        send(4)
        assertEquals(6, socket.pollCount)
    }

    private class RecordingSocket : ClientSocket(42) {
        var ready = false
        var pollCount = 0
        val events = mutableListOf<Byte>()

        override fun isWritable(): Boolean {
            pollCount++
            return ready
        }

        override fun write(data: ByteBuffer) {
            while (data.hasRemaining()) events += data.get()
        }
    }

    @Implements(value = ClientSocket::class, isInAndroidSdk = false)
    class HeadlessClientSocket {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }
}
