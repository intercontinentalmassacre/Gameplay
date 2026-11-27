package app.gamenative.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.gamenative.R
import app.gamenative.ui.data.MainState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.theme.PluviaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BootingSplashTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun slowRuntimeCanBeRevealedAndLateStatusKeepsSplashHidden() {
        val stage = "Preparing game environment"
        val lateStage = "Installing prerequisites"
        val slowMessage = compose.activity.getString(R.string.game_startup_taking_longer)
        val revealLabel = compose.activity.getString(R.string.game_startup_show_screen)
        val state = mutableStateOf(
            MainState(currentScreen = PluviaScreen.Home, showBootingSplash = true, bootingSplashText = stage),
        )
        var revealCount = 0
        compose.setContent {
            PluviaTheme {
                BootingSplash(state.value, onShowGameScreen = {
                    revealCount++
                    state.value = state.value.copy(showBootingSplash = false, bootingSplashTakingLonger = false)
                })
            }
        }

        compose.onNodeWithText(stage).assertIsDisplayed()
        compose.onNodeWithText(revealLabel).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(currentScreen = PluviaScreen.XServer) }
        compose.onNodeWithText(slowMessage).assertDoesNotExist()
        compose.onNodeWithText(revealLabel).assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(bootingSplashTakingLonger = true) }
        compose.onNodeWithText(slowMessage).assertIsDisplayed()
        compose.onNodeWithText(revealLabel).performClick()
        compose.runOnIdle { state.value = state.value.withBootingSplashText(lateStage) }

        compose.onNodeWithText(stage).assertDoesNotExist()
        compose.onNodeWithText(lateStage).assertDoesNotExist()
        compose.onNodeWithText(revealLabel).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, revealCount) }
    }
}
