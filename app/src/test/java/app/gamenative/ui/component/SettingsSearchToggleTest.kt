package app.gamenative.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchToggleTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var lastQuery: String = ""

    private fun setContent(
        onClose: () -> Unit = {},
        initialQuery: String = "",
    ) {
        composeTestRule.setContent {
            var active by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf(initialQuery) }
            PluviaTheme {
                SettingsSearchToggle(
                    active = active,
                    query = query,
                    onQueryChange = {
                        query = it
                        lastQuery = it
                    },
                    onOpen = { active = true },
                    onClose = {
                        active = false
                        query = ""
                        onClose()
                    },
                )
            }
        }
    }

    private val searchDescription: String
        get() = composeTestRule.activity.getString(app.gamenative.R.string.settings_search)

    @Test
    fun `magnifier button opens field and moves focus into it`() {
        setContent()

        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(searchDescription).performClick()

        composeTestRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `typing updates the query`() {
        setContent()
        composeTestRule.onNodeWithContentDescription(searchDescription).performClick()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("wine")

        composeTestRule.waitForIdle()
        assertEquals("wine", lastQuery)
    }

    @Test
    fun `back closes search and restores focus to the button`() {
        var closed = 0
        composeTestRule.setContent {
            var active by remember { mutableStateOf(true) }
            var query by remember { mutableStateOf("wine") }
            PluviaTheme {
                SettingsSearchToggle(
                    active = active,
                    query = query,
                    onQueryChange = { query = it },
                    onOpen = { active = true },
                    onClose = {
                        closed++
                        active = false
                        query = ""
                    },
                )
            }
        }

        composeTestRule.onRoot().performKeyInput { pressKey(Key.Back) }

        composeTestRule.waitForIdle()
        assertEquals(1, closed)
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(searchDescription).assertIsFocused()
    }
}
