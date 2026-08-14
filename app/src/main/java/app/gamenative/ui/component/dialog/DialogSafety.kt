package app.gamenative.ui.component.dialog

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the host Activity is still safe to host a window.
 *
 * Compose's `Dialog` and `AlertDialog` add their own [android.view.Window] via
 * `WindowManager.addView` in `DisposableEffect.onRemembered`. If that runs while
 * the host Activity is finishing or destroyed the window token is `null` and
 * Android throws `WindowManager$BadTokenException`, taking the whole process
 * down. The crash presents as a `Dialog` (or one of its derivatives) being
 * shown during the same frame the activity tears down (e.g. user pressing
 * back out of a screen that triggers a deferred dialog state update).
 *
 * Gate `Dialog(...)` calls on this so the composition short-circuits when the
 * activity is already going away.
 */
@Composable
fun rememberHostActivityActive(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val activity = context as? Activity
        activity == null || !(activity.isFinishing || activity.isDestroyed)
    }
}