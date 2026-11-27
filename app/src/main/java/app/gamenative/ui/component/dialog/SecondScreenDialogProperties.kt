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
 * presentation host provides its own window type and token so dialogs opened from
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
 * current composition is hosted inside a Presentation.
 */
@Composable
fun secondScreenDialogProperties(base: DialogProperties = DialogProperties()): DialogProperties {
    val windowType = LocalSecondScreenDialogWindowType.current ?: return base
    // Nested dialogs inherit the Presentation's token from its own ComposeView.
    val windowToken = LocalSecondScreenDialogWindowToken.current ?: LocalView.current.rootView.windowToken
    return DialogProperties(
        dismissOnBackPress = base.dismissOnBackPress,
        dismissOnClickOutside = base.dismissOnClickOutside,
        usePlatformDefaultWidth = base.usePlatformDefaultWidth,
        decorFitsSystemWindows = base.decorFitsSystemWindows,
        windowType = windowType,
        windowToken = windowToken,
    )
}
