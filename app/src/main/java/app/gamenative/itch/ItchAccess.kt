package app.gamenative.itch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Hilt entry point for the itch.io objects used from non-injected UI code. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ItchEntryPoint {
    fun itchAuthManager(): ItchAuthManager
    fun itchLibraryRepository(): ItchLibraryRepository
    fun itchDownloadManager(): ItchDownloadManager
    fun itchInstallCoordinator(): ItchInstallCoordinator
}

object ItchAccess {
    private fun entryPoint(context: Context): ItchEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, ItchEntryPoint::class.java)

    fun auth(context: Context): ItchAuthManager = entryPoint(context).itchAuthManager()

    fun repository(context: Context): ItchLibraryRepository = entryPoint(context).itchLibraryRepository()

    fun downloads(context: Context): ItchDownloadManager = entryPoint(context).itchDownloadManager()

    fun installer(context: Context): ItchInstallCoordinator = entryPoint(context).itchInstallCoordinator()
}

/**
 * Sign-in/sign-out helpers for composables. Login always opens the *system
 * browser* (Custom Tabs when available, ACTION_VIEW otherwise): itch.io's own
 * guidance is to avoid embedded WebViews so the address bar, password managers
 * and 2FA keep working.
 */
object ItchAuthUi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts the login. PKCE generation and the encrypted pending-session write
     * happen off the main thread; the browser is opened on the main thread.
     */
    fun signIn(context: Context, onError: (String) -> Unit = {}) {
        scope.launch {
            val auth = runCatching { ItchAccess.auth(context) }.getOrElse { error ->
                withContext(Dispatchers.Main) {
                    onError(error.message ?: context.getString(app.gamenative.R.string.itch_login_failed))
                }
                return@launch
            }
            if (!auth.isConfigured()) {
                withContext(Dispatchers.Main) {
                    onError(context.getString(app.gamenative.R.string.itch_login_not_configured))
                }
                return@launch
            }
            val request = auth.beginAuthorization().getOrElse { error ->
                withContext(Dispatchers.Main) {
                    onError(error.message ?: context.getString(app.gamenative.R.string.itch_login_failed))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val uri = Uri.parse(request.url)
                val result = runCatching {
                    val intent = CustomTabsIntent.Builder().build().intent
                    intent.data = uri
                    if (context !is android.app.Activity) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    val fallback = Intent(Intent.ACTION_VIEW, uri)
                    if (context !is android.app.Activity) {
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                }
                result.onFailure { error ->
                    onError(error.message ?: context.getString(app.gamenative.R.string.itch_login_failed))
                }
            }
        }
    }

    fun signOut(context: Context) {
        scope.launch {
            val result = ItchAccess.repository(context).signOut()
            PluviaApp.events.emitJava(
                AndroidEvent.ItchAuthStateChanged(
                    success = result.isSuccess,
                    message = result.exceptionOrNull()?.message,
                    signedOut = true,
                ),
            )
        }
    }

    fun isConfigured(context: Context): Boolean = ItchAccess.auth(context).isConfigured()

    /** True when a stored token can actually be decrypted, not just when the file exists. */
    fun hasAccount(context: Context): Boolean =
        runCatching { ItchAccess.auth(context).accountOrNull() != null }.getOrDefault(false)
}
