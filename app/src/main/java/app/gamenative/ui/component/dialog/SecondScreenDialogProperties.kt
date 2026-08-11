package app.gamenative.ui.component.dialog

import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.utils.rememberHasExternalDisplay

/**
 * Window type/token for dialogs opened from second-screen (Presentation) content.
 *
 * A Presentation window is TYPE_PRESENTATION (2037). Compose Dialog() creates its
 * own window with LayoutParams.type TYPE_APPLICATION (2) and Android 13+ rejects
 * that on a secondary display with "BadTokenException: token null". The
 * presentation host provides its window token here so dialogs opened from
 * settings / quick-menu / game-card content bind to the same presentation window
 * instead of crashing. On the main display these locals stay null and dialogs
 * behave as before.
 */
val LocalSecondScreenDialogWindowType = staticCompositionLocalOf<Int?> { null }
val LocalSecondScreenDialogWindowToken = staticCompositionLocalOf<IBinder?> { null }

/**
 * Moves a modal surface to the interactive display in dual-screen mode while
 * keeping the exact same content on the main display in single-screen mode.
 * Nested dialogs are left in their current Presentation so submenus never jump
 * back to the upper screen.
 */
@Composable
fun SecondScreenRoutedDialog(
    visible: Boolean = true,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (hasExternalDisplay && !alreadyOnSecondScreen) {
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = onDismissRequest,
                    settingsContent = content,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    content()
}

/**
 * Returns [base] enriched with the second-screen window type/token when the
 * current composition is hosted inside a Presentation (both locals set).
 */
@Composable
fun secondScreenDialogProperties(base: DialogProperties = DialogProperties()): DialogProperties {
    val windowType = LocalSecondScreenDialogWindowType.current
    var windowToken = LocalSecondScreenDialogWindowToken.current
    if (windowType != null && windowToken == null) {
        // The published token is read right after Presentation.show(), before the
        // window is attached, so it can be null. By dialog time the Compose view
        // inside the presentation is attached, so its root token is the
        // presentation window token.
        windowToken = LocalView.current.rootView?.windowToken
        if (DEBUG_LOCAL_LOGS) {
            android.util.Log.d(
                "SecondScreenDialogProps",
                "token fallback via LocalView: ${windowToken != null}",
            )
        }
    }
    if (windowType == null || windowToken == null) {
        if (DEBUG_LOCAL_LOGS) {
            android.util.Log.d(
                "SecondScreenDialogProps",
                "SKIPPED type=$windowType token=${windowToken != null} from=${Thread.currentThread().stackTrace[2].methodName}",
            )
        }
        return base
    }
    val result = DialogProperties(
        dismissOnBackPress = base.dismissOnBackPress,
        dismissOnClickOutside = base.dismissOnClickOutside,
        usePlatformDefaultWidth = base.usePlatformDefaultWidth,
        decorFitsSystemWindows = base.decorFitsSystemWindows,
        windowType = windowType,
        windowToken = windowToken,
    )
    if (DEBUG_LOCAL_LOGS) {
        android.util.Log.d("SecondScreenDialogProps", "applied type=$windowType token=${windowToken != null} from=${Thread.currentThread().stackTrace[2].methodName}")
    }
    return result
}

private const val DEBUG_LOCAL_LOGS = true
