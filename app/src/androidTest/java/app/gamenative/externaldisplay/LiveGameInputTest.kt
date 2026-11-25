package app.gamenative.externaldisplay

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.descendants
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.QuickMenuAction
import app.gamenative.ui.gcds.GcdsQuickMenu
import app.gamenative.ui.gcds.GcdsQuickMenuRole
import app.gamenative.ui.theme.GameplayAppearance
import app.gamenative.ui.theme.PluviaTheme
import com.materialkolor.PaletteStyle
import com.winlator.renderer.XServerRenderer
import com.winlator.widget.TouchpadView
import com.winlator.xserver.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy

class LiveGameInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lowerDisplayAcceptsClicksAndKeysAndLeavesGamepadFocusWithGame() {
        val activity = compose.activity
        val display = findGamePresentationDisplay(activity)
        assumeNotNull(display)
        val initialEnabled = PrefManager.dualScreenLauncher
        var presentation: DsHomePresentation? = null
        val visible = mutableStateOf(true)
        var resumeRequested = false
        val clicks = mutableListOf<Pointer.Button>()
        val keys = mutableListOf<Pair<XKeycode, Boolean>>()
        try {
            val server = compose.runOnUiThread {
                PrefManager.dualScreenLauncher = true
                object : XServer(ScreenInfo(640, 480), false) {
                    override fun injectPointerButtonPress(button: Pointer.Button) { clicks.add(button) }
                    override fun injectKeyPress(key: XKeycode) { keys.add(key to true) }
                    override fun injectKeyRelease(key: XKeycode) { keys.add(key to false) }
                }.apply {
                    setRenderer(Proxy.newProxyInstance(XServerRenderer::class.java.classLoader,
                        arrayOf(XServerRenderer::class.java)) { _, method, _ ->
                        if (method.name == "isFullscreen") false else null
                    } as XServerRenderer)
                }
            }
            compose.setContent {
                PluviaTheme {
                    GcdsQuickMenu(role = GcdsQuickMenuRole.GAME, isVisible = visible.value,
                        gameInputServer = server, onDismiss = { visible.value = false },
                        onRequestOpen = { visible.value = true },
                        onItemSelected = { resumeRequested = it == QuickMenuAction.LIVE_INPUT; true })
                }
            }
            compose.runOnIdle {
                presentation = DsHomePresentation(activity, display!!, mutableStateOf(
                    GameplayAppearance(true, false, PaletteStyle.TonalSpot, null, false)))
                presentation!!.show()
                DsHomeSecondScreen.markPresentationActive(true)
            }
            selectTab(R.string.live_input_tab, DsHomeSecondScreen.Mode.GAME_INPUT)
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(resumeRequested)
                assertFalse(visible.value)
                val window = presentation!!.window!!
                assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                val views = (window.decorView as ViewGroup).descendants
                val pad = views.filterIsInstance<TouchpadView>().single()
                tap(pad)
                assertTrue(clicks.contains(Pointer.Button.BUTTON_LEFT))
                views.filterIsInstance<ImageButton>().single().performClick()
                val key = views.filterIsInstance<Button>().first { it.text.toString().equals("w", true) }
                assertTrue(key.isShown)
                tap(key)
                assertEquals(listOf(XKeycode.KEY_W to true, XKeycode.KEY_W to false), keys)
            }
            selectTab(R.string.second_screen_tab_game, DsHomeSecondScreen.Mode.GAME_DASHBOARD)
            selectTab(R.string.live_input_tab, DsHomeSecondScreen.Mode.GAME_INPUT)
            selectTab(R.string.quick_menu_title, DsHomeSecondScreen.Mode.QUICK_MENU)
            selectTab(R.string.second_screen_tab_game, DsHomeSecondScreen.Mode.GAME_DASHBOARD)
            selectTab(R.string.quick_menu_title, DsHomeSecondScreen.Mode.QUICK_MENU)
            selectTab(R.string.live_input_tab, DsHomeSecondScreen.Mode.GAME_INPUT)
            // The hardware menu shortcut temporarily covers the selected input panel.
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(5000) { DsHomeSecondScreen.model?.mode == DsHomeSecondScreen.Mode.QUICK_MENU }
            compose.runOnIdle {
                resumeRequested = false
                visible.value = false
            }
            compose.waitUntil(5000) { resumeRequested && DsHomeSecondScreen.model?.mode == DsHomeSecondScreen.Mode.GAME_INPUT }
        } finally {
            compose.runOnUiThread {
                DsHomeSecondScreen.markPresentationActive(false)
                presentation?.dismiss()
                PrefManager.dualScreenLauncher = initialEnabled
            }
        }
    }

    private fun selectTab(label: Int, expected: DsHomeSecondScreen.Mode) {
        compose.onNode(hasText(compose.activity.getString(label)) and hasClickAction())
            .performTouchInput { click() }
        compose.waitUntil(5000) { DsHomeSecondScreen.model?.mode == expected }
    }

    private fun tap(view: View) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 24f, 24f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
