package app.gamenative.ui.component.dialog

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PhysicalControllerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun keyboardMouseLayoutAppliesOnlyToThisGameAndSurvivesReopening() {
        val context = compose.activity
        val manager = InputControlsManager(context)
        val source = requireNotNull(manager.getProfile(0))
        val game = manager.duplicateProfile(source)
        try {
            val visible = mutableStateOf(true)
            compose.setContent {
                PluviaTheme {
                    if (visible.value) PhysicalControllerConfigSection(
                        profile = game,
                        onDismiss = { visible.value = false },
                        resolveSaveProfile = { game },
                        onSave = { visible.value = false },
                        embedded = true, layoutsOnly = true,
                    )
                }
            }
            val keyboardMouse = context.getString(R.string.layout_keyboard_mouse)
            val apply = context.getString(R.string.layout_apply)
            compose.onNodeWithText(keyboardMouse).performClick()
            compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithText(apply).assertIsNotEnabled()
            compose.onNodeWithText(keyboardMouse).performClick()
            compose.onNodeWithText(apply).performClick()

            compose.runOnIdle {
                assertFalse(visible.value)
                val saved = requireNotNull(ControlsProfile(context, game.id).getController("*"))
                assertEquals(Binding.KEY_W, saved.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)).binding)
                assertEquals(Binding.MOUSE_MOVE_UP, saved.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_RZ, -1)).binding)
                assertEquals(Binding.MOUSE_LEFT_BUTTON, saved.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2).binding)
                assertEquals(Binding.KEY_SPACE, saved.getControllerBinding(KeyEvent.KEYCODE_BUTTON_A).binding)
                assertEquals(Binding.GAMEPAD_BUTTON_A, source.getController("*").getControllerBinding(KeyEvent.KEYCODE_BUTTON_A).binding)
                visible.value = true
            }
            compose.onNodeWithText(apply).assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.gamepad)).performClick()
            compose.onNodeWithText(apply).performClick()
            compose.runOnIdle {
                assertEquals(Binding.GAMEPAD_BUTTON_A, ControlsProfile(context, game.id).getController("*")
                    .getControllerBinding(KeyEvent.KEYCODE_BUTTON_A).binding)
            }
        } finally {
            manager.removeProfile(game)
        }
    }
}
