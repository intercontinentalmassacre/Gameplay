package app.gamenative.ui.component.dialog

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "land")
class PhysicalControllerBindingDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun waitUntilTextDisappears(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun cancellingPickerAndEditorKeepsTheOriginalMapping() {
        val profile = profileWithEnterBinding(1_891)
        val profileFile = ControlsProfile.getProfileFile(compose.activity, profile.id)
        val buttonLabel = compose.activity.getString(R.string.button_a)
        val pickerTitle = compose.activity.getString(R.string.bind_button, buttonLabel)
        val editorTitle = compose.activity.getString(R.string.physical_controller_config)
        val currentBinding = compose.activity.getString(R.string.current_binding, Binding.KEY_ENTER.toString())
        val backLabel = compose.activity.getString(R.string.back)
        val closeLabel = compose.activity.getString(R.string.close)
        val editorVisible = mutableStateOf(true)
        var editorDismissals = 0
        var saveResolutions = 0
        var saves = 0

        compose.setContent {
            PluviaTheme {
                if (editorVisible.value) {
                    PhysicalControllerConfigSection(
                        profile = profile,
                        onDismiss = {
                            editorDismissals++
                            editorVisible.value = false
                        },
                        resolveSaveProfile = {
                            saveResolutions++
                            profile
                        },
                        onSave = { saves++ },
                        embedded = true,
                    )
                }
            }
        }

        val bindingRow = compose.onNode(
            hasClickAction() and hasText(buttonLabel) and hasText(Binding.KEY_ENTER.toString()),
        )
        bindingRow.performClick()
        compose.onNodeWithText(pickerTitle).assertExists()
        compose.onNodeWithText(currentBinding).assertExists()

        compose.onNode(hasClickAction() and hasContentDescription(backLabel)).performClick()

        compose.onNodeWithText(pickerTitle).assertDoesNotExist()
        compose.onNodeWithText(editorTitle).assertExists()
        bindingRow.assertExists()

        bindingRow.performClick()
        val space = Binding.KEY_SPACE.toString()
        compose.onNode(hasClickAction() and hasText(space)).performScrollTo().performClick()
        waitUntilTextDisappears(pickerTitle)

        // Reopen the same button's picker to check its draft, independently of
        // how the editor merges the row label and the binding subtitle.
        compose.onNode(hasClickAction() and hasText(buttonLabel)).performClick()
        compose.onNodeWithText(pickerTitle).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.current_binding, space)).assertExists()
        compose.onNode(hasClickAction() and hasContentDescription(backLabel)).performClick()
        waitUntilTextDisappears(pickerTitle)

        compose.onNode(hasClickAction() and hasContentDescription(closeLabel)).performClick()
        compose.runOnIdle {
            assertEquals(1, editorDismissals)
            assertEquals(0, saveResolutions)
            assertEquals(0, saves)
            assertEquals(Binding.KEY_ENTER, profile.buttonABinding())
            assertFalse(profileFile.exists())
        }
        waitUntilTextDisappears(editorTitle)
    }

    @Test
    fun savingWritesOnlyTheResolvedProfile() {
        val source = profileWithEnterBinding(1_892)
        val target = ControlsProfile(compose.activity, 1_893).apply { name = "Test target" }
        val buttonLabel = compose.activity.getString(R.string.button_a)
        val saveLabel = compose.activity.getString(R.string.save)
        var savedProfile: ControlsProfile? = null

        compose.setContent {
            PluviaTheme {
                PhysicalControllerConfigSection(
                    profile = source,
                    onDismiss = {},
                    resolveSaveProfile = { target },
                    onSave = { savedProfile = it },
                    embedded = true,
                )
            }
        }

        compose.onNode(hasClickAction() and hasText(buttonLabel)).performClick()
        compose.onNode(hasClickAction() and hasText(Binding.KEY_SPACE.toString())).performScrollTo().performClick()
        compose.onNode(hasClickAction() and hasContentDescription(saveLabel)).performClick()

        compose.runOnIdle {
            assertSame(target, savedProfile)
            assertEquals(Binding.KEY_ENTER, source.buttonABinding())
            assertFalse(ControlsProfile.getProfileFile(compose.activity, source.id).exists())
            val reloadedTarget = ControlsProfile(compose.activity, target.id)
            assertEquals(Binding.KEY_SPACE, reloadedTarget.buttonABinding())
        }
    }

    private fun profileWithEnterBinding(id: Int): ControlsProfile =
        ControlsProfile(compose.activity, id).apply {
            addController("*").addControllerBinding(ExternalControllerBinding().apply {
                setKeyCode(KeyEvent.KEYCODE_BUTTON_A)
                setBinding(Binding.KEY_ENTER)
            })
        }

    private fun ControlsProfile.buttonABinding(): Binding? =
        getController("*")?.controllerBindings
            ?.singleOrNull { it.getKeyCodeForAxis() == KeyEvent.KEYCODE_BUTTON_A }
            ?.getBinding()
}
