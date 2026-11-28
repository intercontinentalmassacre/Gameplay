package app.gamenative.externaldisplay

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.theme.currentGameplayAppearance
import app.gamenative.utils.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object BuranQuickMenuSessions {
    const val EXTRA_MENU = "org.buran.runtime.GAMEPLAY_QUICK_MENU"
    const val EXTRA_MENU_BUTTON = "org.buran.runtime.GAMEPLAY_QUICK_MENU_BUTTON"

    data class Session(
        val id: String,
        val controls: StateFlow<BuranCompanionControls?>,
        val menu: PendingIntent,
    )

    private val current = MutableStateFlow<Session?>(null)
    val sessions = current.asStateFlow()

    fun openToken(context: Context, id: String, controls: StateFlow<BuranCompanionControls?>): PendingIntent {
        current.value?.menu?.cancel()
        val intent = Intent(context, BuranQuickMenuActivity::class.java)
            .setData(Uri.Builder().scheme("gameplay-quick-menu").authority("session").appendPath(id).build())
        val options = if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions.makeBasic().apply {
                pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }.toBundle()
        } else null
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE, options)
        current.value = Session(id, controls, pendingIntent)
        return pendingIntent
    }

    fun close(id: String?) {
        val session = current.value ?: return
        if (session.id != id) return
        current.value = null
        session.menu.cancel()
    }
}

class BuranQuickMenuActivity : ComponentActivity() {
    private var keyboardVisible by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase, PrefManager.appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.data?.lastPathSegment
        val session = BuranQuickMenuSessions.sessions.value
        val initialControls = session?.controls?.value
        if (session == null || session.id != id || initialControls == null ||
            initialControls.hudOnLowerScreen || initialControls.stopping
        ) {
            finish()
            return
        }
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent {
            val activeSession by BuranQuickMenuSessions.sessions.collectAsState()
            val channel by session.controls.collectAsState()
            val controls = channel
            val valid = activeSession === session && controls != null && !controls.hudOnLowerScreen && !controls.stopping
            LaunchedEffect(valid) { if (!valid) finish() }
            BackHandler(onBack = ::returnToGameOrMenu)
            if (valid && controls != null) {
                currentGameplayAppearance().Provide {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                                .clickable(onClick = ::returnToGameOrMenu),
                        )
                        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                            val pane = Modifier.widthIn(max = 680.dp).fillMaxWidth(0.94f).fillMaxHeight(0.93f)
                            if (keyboardVisible) {
                                Surface(modifier = pane) {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        TextButton(onClick = { keyboardVisible = false }) {
                                            Text(stringResource(R.string.back))
                                        }
                                        AndroidView(
                                            modifier = Modifier.fillMaxWidth(),
                                            factory = { context ->
                                                ExternalOnScreenKeyboardView(context, controls::key).apply {
                                                    post { requestControllerFocus() }
                                                }
                                            },
                                        )
                                    }
                                }
                            } else {
                                BuranSessionQuickMenu(
                                    channel = controls,
                                    onDismiss = ::finish,
                                    onKeyboard = { keyboardVisible = true },
                                    modifier = pane,
                                    upperScreen = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) returnToGameOrMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun returnToGameOrMenu() {
        if (keyboardVisible) keyboardVisible = false else finish()
    }
}
