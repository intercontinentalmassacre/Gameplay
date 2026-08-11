package app.gamenative.ui.screen.settings

import androidx.compose.runtime.Composable
import app.gamenative.ui.component.dialog.ConsoleSettingsPage

/** Shared full-screen shell for settings managers used from the console UI. */
@Composable
internal fun ConsoleManagerDialog(
    open: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ConsoleSettingsPage(
        visible = open,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        content()
    }
}
