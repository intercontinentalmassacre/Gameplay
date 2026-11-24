package com.winlator.xserver

import android.app.Application
import com.winlator.xserver.InputDeviceManagerButtonReleaseTest.HeadlessDrawable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HeadlessDrawable::class])
class PointerMotionTest {
    @Test
    fun relativeMotionReturnsFromAnAbsolutePositionOutsideTheScreenOnTheFirstStep() {
        val server = XServer(ScreenInfo(640, 480), false)
        server.injectPointerMove(700, -60)
        assertEquals(700, server.pointer.x.toInt())
        assertEquals(-60, server.pointer.y.toInt())

        server.injectPointerMoveDelta(-3, 4)

        assertEquals(636, server.pointer.x.toInt())
        assertEquals(4, server.pointer.y.toInt())
    }
}
