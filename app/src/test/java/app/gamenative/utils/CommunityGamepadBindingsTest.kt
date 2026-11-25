package app.gamenative.utils

import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.service.CommunityControllerLayouts
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.*
import org.junit.Test

class CommunityGamepadBindingsTest {
    @Test fun queryTargetsOnlyPublicLayoutsOfThisGame() {
        val request = CommunityControllerLayouts.request(335320, " BoS ", 2)
        assertEquals(241100, request.appid)
        assertEquals(15, request.filetype)
        assertEquals(2, request.page)
        assertEquals("BoS", request.searchText)
        assertEquals(mapOf("app" to "335320", "visibility" to "public", "deleted" to "0"),
            request.requiredKvTagsList.associate { it.key to it.value })
    }

    @Test fun xboxLayoutImportsKeysAimAndTriggerClicks() {
        val text = javaClass.getResource("/sc/controller_xboxone_wasd.vdf")!!.readText()
        val bindings = CommunityGamepadBindings.read(text)
        assertEquals(Binding.KEY_SPACE, bindings[KeyEvent.KEYCODE_BUTTON_A])
        assertEquals(Binding.KEY_W, bindings[ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)])
        assertEquals(Binding.MOUSE_MOVE_LEFT, bindings[ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Z, -1)])
        assertEquals(Binding.MOUSE_LEFT_BUTTON, bindings[KeyEvent.KEYCODE_BUTTON_R2])
        assertEquals(Binding.MOUSE_RIGHT_BUTTON, bindings[KeyEvent.KEYCODE_BUTTON_L2])
        assertEquals(Binding.OPEN_NAVIGATION_MENU, bindings[KeyEvent.KEYCODE_BUTTON_MODE])
    }

    @Test fun unsupportedLayoutCannotReplaceCurrentProfile() {
        assertTrue(CommunityGamepadBindings.read("\"controller_mappings\" { \"title\" \"empty\" }").isEmpty())
    }
}
