package app.gamenative.ui.component.dialog

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PhysicalControllerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun namedLayoutCanBeCopiedToAnotherGame() {
        val context = compose.activity
        val name = "Controller test ${UUID.randomUUID()}"
        val original = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to Binding.KEY_SPACE,
            ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1) to Binding.KEY_W,
        )
        val otherGame = mapOf(KeyEvent.KEYCODE_BUTTON_A to Binding.GAMEPAD_BUTTON_A)
        val game = mutableStateOf(1)
        var applied: Map<Int, Binding>? = null
        try {
            compose.setContent {
                PluviaTheme {
                    androidx.compose.runtime.key(game.value) {
                        PhysicalControllerLayoutPicker(
                            gameName = "Game ${game.value}",
                            steamAppId = null,
                            embedded = true,
                            current = if (game.value == 1) original else otherGame,
                            defaults = otherGame,
                            onApply = { applied = it },
                            onDismiss = {},
                        )
                    }
                }
            }
            compose.onNodeWithText(context.getString(R.string.controller_profile_save_as)).performClick()
            compose.onNode(hasSetTextAction()).performTextInput(name)
            compose.onNodeWithText(context.getString(R.string.save)).performClick()
            val savedHeading = context.getString(R.string.controller_profiles_saved)
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText(savedHeading).fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle { game.value = 2 }
            compose.onNodeWithText(name).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.layout_apply)).performClick()
            compose.runOnIdle { assertEquals(original, applied) }
        } finally {
            context.getSharedPreferences("physical_controller_layouts", Context.MODE_PRIVATE).edit().remove(name).commit()
        }
    }

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
