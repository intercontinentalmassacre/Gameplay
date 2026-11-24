package com.winlator.widget

import android.app.Activity
import android.app.Application
import android.view.InputDevice
import android.view.View
import android.widget.FrameLayout
import app.gamenative.ui.screen.xserver.isMouseOrTouchpadSource
import com.winlator.renderer.XServerRenderer
import com.winlator.xserver.InputDeviceManagerButtonReleaseTest.HeadlessDrawable
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [HeadlessDrawable::class])
class TouchpadPointerFocusTest {
    @Test
    fun captureEnabledTouchpadTakesFocusWhenAttached() {
        assertFocusAfterAttaching(captureEnabled = true)
    }

    @Test
    fun captureDisabledTouchpadLeavesFocusOnExistingView() {
        assertFocusAfterAttaching(captureEnabled = false)
    }

    @Test
    fun overlayBlocksTouchpadFromTakingFocus() {
        assertFocusAfterAttaching(captureEnabled = true, captureAllowed = false)
    }

    @Test
    fun onlyMouseAndTouchpadSourcesCanTriggerCapture() {
        assertTrue(isMouseOrTouchpadSource(InputDevice.SOURCE_MOUSE))
        assertTrue(isMouseOrTouchpadSource(InputDevice.SOURCE_MOUSE_RELATIVE))
        assertTrue(isMouseOrTouchpadSource(InputDevice.SOURCE_TOUCHPAD))
        assertFalse(isMouseOrTouchpadSource(InputDevice.SOURCE_JOYSTICK))
        assertFalse(isMouseOrTouchpadSource(InputDevice.SOURCE_GAMEPAD))
        assertFalse(isMouseOrTouchpadSource(InputDevice.SOURCE_TOUCHSCREEN))
        assertFalse(isMouseOrTouchpadSource(InputDevice.SOURCE_STYLUS))
    }

    private fun assertFocusAfterAttaching(captureEnabled: Boolean, captureAllowed: Boolean = true) {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            val host = FrameLayout(activity)
            val previousFocus = View(activity).apply {
                isFocusableInTouchMode = true
            }
            host.addView(previousFocus)
            activity.setContentView(host)
            assertTrue(previousFocus.requestFocus())

            val server = XServer(ScreenInfo(640, 480), false).apply {
                setRenderer(mock<XServerRenderer>())
            }
            val touchpad = TouchpadView(activity, server, captureEnabled)
            touchpad.setPointerCaptureAllowed { captureAllowed }
            host.addView(touchpad)

            assertTrue((if (captureEnabled && captureAllowed) touchpad else previousFocus).hasFocus())
        }
    }
}
