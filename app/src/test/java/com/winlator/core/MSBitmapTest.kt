package com.winlator.core

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MSBitmapTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun writesBottomUpBgraWithOpaqueAlpha() {
        val bitmap = Bitmap.createBitmap(
            intArrayOf(Color.RED, Color.BLUE, Color.argb(128, 0, 255, 0), Color.WHITE),
            2, 2, Bitmap.Config.ARGB_8888,
        )
        val file = temporary.newFile("wallpaper.bmp")

        assertTrue(MSBitmap.create(bitmap, file))
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(70, bytes.getInt(2))
        assertEquals(32, bytes.getShort(28).toInt())
        assertEquals(16, bytes.getInt(34))
        assertEquals(Color.GREEN, bytes.getInt(54))
        assertEquals(Color.WHITE, bytes.getInt(58))
        assertEquals(Color.RED, bytes.getInt(62))
        assertEquals(Color.BLUE, bytes.getInt(66))

        val decoded = checkNotNull(MSBitmap.open(file))
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
    }

    @Test
    fun opensLegacy24BitBitmapWithRowPadding() {
        val file = temporary.newFile("legacy.bmp")
        val bytes = ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(0, 0x4d42)
        bytes.putInt(2, 62)
        bytes.putInt(10, 54)
        bytes.putInt(14, 40)
        bytes.putInt(18, 2)
        bytes.putInt(22, 1)
        bytes.putShort(26, 1)
        bytes.putShort(28, 24)
        bytes.putInt(34, 8)
        bytes.put(56, 0xff.toByte()) // Red, then a blue pixel and two pad bytes.
        bytes.put(57, 0xff.toByte())
        file.writeBytes(bytes.array())

        val decoded = checkNotNull(MSBitmap.open(file))
        assertEquals(2, decoded.width)
        assertEquals(1, decoded.height)
    }
}
