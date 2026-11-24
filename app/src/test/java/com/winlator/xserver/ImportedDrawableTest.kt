package com.winlator.xserver

import android.app.Application
import com.winlator.renderer.NativeTexture
import com.winlator.xserver.InputDeviceManagerButtonReleaseTest.HeadlessDrawable
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HeadlessDrawable::class])
class ImportedDrawableTest {
    @Test
    fun importedTextureSuppliesStorageEvenInSurfaceRendererMode() {
        val server = XServer(ScreenInfo(640, 480), false)
        val storage = ByteBuffer.allocateDirect(128)
        val image = object : NativeTexture() {
            override fun getStride(): Short = 16
            override fun getVirtualData(): ByteBuffer = storage
        }
        val previousSurfaceMode = Drawable.IS_ASR()
        try {
            // In this mode a normal Drawable allocates an Android hardware buffer.
            Drawable.DRAWABLE_ASR_MODE(true)
            val drawable = server.drawableManager.createDrawableFromTexture(42, 2, 24, image)

            assertSame(image, drawable.texture)
            assertSame(storage, drawable.data)
            assertEquals(16, drawable.width.toInt())
            assertSame(drawable, server.drawableManager.getDrawable(42))
        } finally {
            Drawable.DRAWABLE_ASR_MODE(previousSurfaceMode)
        }
    }
}
