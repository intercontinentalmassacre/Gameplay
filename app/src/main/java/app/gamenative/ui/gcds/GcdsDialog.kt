package app.gamenative.ui.gcds

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.ui.component.dialog.secondScreenDialogProperties

/**
 * Presentation-safe dialog: routes through secondScreenDialogProperties so it binds to
 * the second-display presentation window instead of crashing with BadToken. Every new
 * dialog in the app must use this wrapper.
 */
@Composable
fun GcdsDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = secondScreenDialogProperties(properties),
        content = content,
    )
}
