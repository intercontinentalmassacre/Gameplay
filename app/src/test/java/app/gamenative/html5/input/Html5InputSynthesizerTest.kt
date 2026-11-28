package app.gamenative.html5.input

import com.winlator.inputcontrols.Binding
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class Html5InputSynthesizerTest {
    @Test
    fun cancelingMousePressReleasesWithoutClicking() {
        val bridge = Html5InputBridge()
        val input = Html5InputSynthesizer(bridge)

        input.onBindingPress(Binding.MOUSE_LEFT_BUTTON, true)
        input.cancelBinding(Binding.MOUSE_LEFT_BUTTON)
        input.onBindingPress(Binding.MOUSE_LEFT_BUTTON, true)
        input.onBindingPress(Binding.MOUSE_LEFT_BUTTON, false)

        val events = JSONArray(bridge.drainQueue())
        assertEquals(
            listOf("mousedown", "mouseup", "mousedown", "mouseup", "click"),
            List(events.length()) { events.getJSONObject(it).getString("type") },
        )
    }
}
