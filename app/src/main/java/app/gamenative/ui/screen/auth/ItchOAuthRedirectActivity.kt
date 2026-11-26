package app.gamenative.ui.screen.auth

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import app.gamenative.itch.ItchPaths
import app.gamenative.itch.StaleItchCallback
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.itch.ItchAccess
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.launch

/**
 * Receives `gameplay://itch-oauth/callback?code=…&state=…` from the system
 * browser, validates the OAuth state and exchanges the code for a token.
 *
 * The activity is intentionally tiny: it owns no UI and finishes immediately.
 * All token handling lives in [app.gamenative.itch.ItchAuthManager].
 */
class ItchOAuthRedirectActivity : ComponentActivity() {
    companion object {
        fun updateRegistration(context: Context) {
            val component = ComponentName(context, ItchOAuthRedirectActivity::class.java)
            val enabled = ItchPaths.pendingAuthorizationFile(context).isFile
            context.packageManager.setComponentEnabledSetting(component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: run {
            finish()
            return
        }
        lifecycleScope.launch {
            val result = ItchAccess.repository(applicationContext).completeAuthorization(uri.toString())
            updateRegistration(applicationContext)
            if (result.exceptionOrNull() is StaleItchCallback) {
                finish()
                return@launch
            }
            result.fold(
                onSuccess = { account ->
                    PluviaApp.events.emitJava(
                        AndroidEvent.ItchAuthStateChanged(
                            success = true,
                            message = account.label,
                        ),
                    )
                },
                onFailure = { error ->
                    PluviaApp.events.emitJava(
                        AndroidEvent.ItchAuthStateChanged(
                            success = false,
                            message = error.message,
                        ),
                    )
                    runCatching {
                        SnackbarManager.show(error.message ?: "itch.io sign-in failed")
                    }
                },
            )
            finish()
        }
    }
}
