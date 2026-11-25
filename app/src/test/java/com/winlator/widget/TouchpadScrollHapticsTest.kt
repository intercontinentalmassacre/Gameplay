package com.winlator.widget

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import com.winlator.renderer.XServerRenderer
import com.winlator.xserver.InputDeviceManagerButtonReleaseTest.HeadlessDrawable
import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33, 35], shadows = [HeadlessDrawable::class])
class TouchpadScrollHapticsTest {
    @Test
    fun singleFingerTravelTicksWhileMovingAndResetsAtLift() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val feedback = mutableListOf<Int>()
            val server = XServer(ScreenInfo(640, 480), false).apply { setRenderer(mock<XServerRenderer>()) }
            val pad = object : TouchpadView(controller.get(), server, false) {
                override fun performHapticFeedback(constant: Int): Boolean = feedback.add(constant)
            }
            pad.layout(0, 0, 640, 480)
            val density = pad.resources.displayMetrics.density
            var enabled = false
            pad.setMotionHapticsEnabled { enabled }
            fun finger(action: Int, xDp: Int) {
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, action, xDp * density, 50f, 0)
                try { pad.onTouchEvent(event) } finally { event.recycle() }
            }
            SystemClock.sleep(100)
            finger(MotionEvent.ACTION_DOWN, 0)
            finger(MotionEvent.ACTION_MOVE, 30)
            assertTrue(feedback.isEmpty())
            enabled = true
            finger(MotionEvent.ACTION_MOVE, 39)
            assertTrue(feedback.isEmpty())
            finger(MotionEvent.ACTION_MOVE, 62)
            finger(MotionEvent.ACTION_MOVE, 92)
            SystemClock.sleep(100)
            finger(MotionEvent.ACTION_MOVE, 92)
            assertEquals(listOf(HapticFeedbackConstants.VIRTUAL_KEY), feedback)
            finger(MotionEvent.ACTION_MOVE, 97)
            finger(MotionEvent.ACTION_MOVE, 105)
            finger(MotionEvent.ACTION_UP, 105)
            SystemClock.sleep(100)
            finger(MotionEvent.ACTION_DOWN, 200)
            finger(MotionEvent.ACTION_MOVE, 208)
            assertEquals(List(2) { HapticFeedbackConstants.VIRTUAL_KEY }, feedback)
            finger(MotionEvent.ACTION_MOVE, 232)
            assertEquals(List(3) { HapticFeedbackConstants.VIRTUAL_KEY }, feedback)
        }
    }

    @Test
    fun onlyWheelStepsWithFeedbackEnabledProduceTicks() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val steps = mutableListOf<Pointer.Button>()
            val feedback = mutableListOf<Int>()
            val server = object : XServer(ScreenInfo(640, 480), false) {
                override fun injectPointerButtonPress(button: Pointer.Button) { steps.add(button) }
                override fun injectPointerButtonRelease(button: Pointer.Button) {}
            }.apply { setRenderer(mock<XServerRenderer>()) }
            val pad = object : TouchpadView(controller.get(), server, false) {
                override fun performHapticFeedback(constant: Int): Boolean = feedback.add(constant)
            }
            pad.layout(0, 0, 640, 480)
            fun touch(action: Int, y: Int, count: Int = 2) {
                val fingers = Array(count) { i -> MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                } }
                val points = Array(count) { i -> MotionEvent.PointerCoords().apply {
                    x = 80f + i * 20
                    this.y = y.toFloat()
                } }
                val now = android.os.SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(0, now, action, count, fingers, points, 0, 0,
                    1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { pad.onTouchEvent(event) } finally { event.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN, 100, count = 1)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 100)
            touch(MotionEvent.ACTION_MOVE, 230)
            assertTrue(steps.isNotEmpty())
            assertTrue(feedback.isEmpty())

            var enabled = true
            pad.setMotionHapticsEnabled { enabled }
            steps.clear()
            touch(MotionEvent.ACTION_MOVE, 232)
            assertTrue(steps.isEmpty())
            assertTrue(feedback.isEmpty())
            touch(MotionEvent.ACTION_MOVE, 365)
            touch(MotionEvent.ACTION_MOVE, 130)
            assertTrue(steps.containsAll(listOf(Pointer.Button.BUTTON_SCROLL_UP, Pointer.Button.BUTTON_SCROLL_DOWN)))
            val tick = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            assertEquals(List(steps.size) { tick }, feedback)

            enabled = false
            feedback.clear()
            steps.clear()
            touch(MotionEvent.ACTION_MOVE, 300)
            assertTrue(steps.isNotEmpty())
            assertTrue(feedback.isEmpty())
        }
    }
}
