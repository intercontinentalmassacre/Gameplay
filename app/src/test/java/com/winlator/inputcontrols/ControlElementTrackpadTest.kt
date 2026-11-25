package com.winlator.inputcontrols

import android.app.Application
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ControlElementTrackpadTest {
    @Test
    fun `acceleration uses the scaled cursor movement`() {
        assertCursorMove(speed = 2f, movement = 4f, expectedDelta = 12)
        assertCursorMove(speed = 0.5f, movement = 8f, expectedDelta = 4)
    }

    private fun assertCursorMove(speed: Float, movement: Float, expectedDelta: Int) {
        val view = mock<InputControlsView>()
        val touchpad = mock<TouchpadView>()
        val server = mock<XServer>()
        val profile = mock<ControlsProfile>()
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(view.xServer).thenReturn(server)
        whenever(view.profile).thenReturn(profile)
        whenever(profile.cursorSpeed).thenReturn(speed)
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0f, 0f), floatArrayOf(movement, 0f),
        )
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(element.handleTouchMove(1, 100f + movement, 100f))
        verify(server).injectPointerMoveDelta(expectedDelta, 0)
    }
}
