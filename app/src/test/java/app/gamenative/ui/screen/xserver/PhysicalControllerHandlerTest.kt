package app.gamenative.ui.screen.xserver

import android.app.Application
import android.graphics.PointF
import android.view.MotionEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PhysicalControllerHandlerTest {
    @Test
    fun `stick bindings can reverse the mouse direction on both axes`() {
        val controller = ExternalController().apply {
            state.thumbLX = 0.75f
            state.thumbLY = -0.5f
            addControllerBinding(ExternalControllerBinding().apply {
                setKeyCode(ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1))
                setBinding(Binding.MOUSE_MOVE_LEFT)
            })
            addControllerBinding(ExternalControllerBinding().apply {
                setKeyCode(ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1))
                setBinding(Binding.MOUSE_MOVE_DOWN)
            })
        }
        val handler = PhysicalControllerHandler(null, null)
        PhysicalControllerHandler::class.java.getDeclaredMethod(
            "processJoystickInput", ExternalController::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(handler, controller, 42)

        val offset = PhysicalControllerHandler::class.java.getDeclaredField("mouseMoveOffset")
            .apply { isAccessible = true }.get(handler) as PointF
        assertEquals(-0.75f, offset.x, 0f)
        assertEquals(0.5f, offset.y, 0f)
    }
}
