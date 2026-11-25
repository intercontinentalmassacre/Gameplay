package app.gamenative.ui.screen.library

import android.view.KeyEvent
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import app.gamenative.ui.screen.library.components.LibrarySearchBar
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibrarySearchFocusTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dpadDownMovesFocusFromNativeSearchToComposeResults() {
        var handoffRequested = false
        compose.setContent {
            PluviaTheme {
                val results = remember { FocusRequester() }
                Column {
                    LibrarySearchBar(
                        isVisible = true,
                        searchQuery = "Example",
                        resultCount = 1,
                        onScrollToTop = {},
                        onSearchQuery = {},
                        onDismiss = {},
                        onNavigateToResults = {
                            handoffRequested = true
                            results.requestFocus()
                        },
                    )
                    Button(onClick = {}, modifier = Modifier.focusRequester(results)) {
                        Text("Example game")
                    }
                }
            }
        }

        onView(isAssignableFrom(EditText::class.java))
            .check(matches(hasFocus()))
            .perform(pressKey(KeyEvent.KEYCODE_DPAD_DOWN))

        compose.onNodeWithText("Example game").assertIsFocused()
        compose.runOnIdle { assertTrue(handoffRequested) }
    }
}
