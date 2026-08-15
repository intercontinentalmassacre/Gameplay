package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.ConsoleDialogButton
import app.gamenative.ui.component.GamepadHint
import app.gamenative.ui.component.GamepadHintRow
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.shouldShowGamepadUI
import app.gamenative.utils.rememberHasExternalDisplay

@Composable
fun MessageDialog(
    visible: Boolean,
    onDismissRequest: (() -> Unit)? = null,
    onConfirmClick: (() -> Unit)? = null,
    onDismissClick: (() -> Unit)? = null,
    onActionClick: (() -> Unit)? = null,
    confirmBtnText: String = "Confirm",
    dismissBtnText: String = "Dismiss",
    actionBtnText: String? = null,
    icon: ImageVector? = null,
    title: String? = null,
    message: String? = null,
    useHtmlInMsg: Boolean = false,
    embedded: Boolean = false,
) {
    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (visible && !embedded && hasExternalDisplay && !alreadyOnSecondScreen) {
        val secondScreenContent: @Composable () -> Unit = {
            MessageDialog(
                visible = true,
                onDismissRequest = onDismissRequest,
                onConfirmClick = onConfirmClick,
                onDismissClick = onDismissClick,
                onActionClick = onActionClick,
                confirmBtnText = confirmBtnText,
                dismissBtnText = dismissBtnText,
                actionBtnText = actionBtnText,
                icon = icon,
                title = title,
                message = message,
                useHtmlInMsg = useHtmlInMsg,
                embedded = true,
            )
        }
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = { (onDismissRequest ?: onDismissClick)?.invoke() },
                    settingsContent = secondScreenContent,
                ),
            )
        }
DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    val hostActive = rememberHostActivityActive()
    when {
        visible && hostActive -> {
            val confirmFocusRequester = remember { FocusRequester() }
            val dismissFocusRequester = remember { FocusRequester() }

            // Controller lands on the safe choice first: dismiss when there is
            // one, confirm otherwise (single-button info dialogs).
            LaunchedEffect(Unit) {
                runCatching {
                    if (onDismissClick != null) {
                        dismissFocusRequester.requestFocus()
                    } else {
                        confirmFocusRequester.requestFocus()
                    }
                }
            }

            AlertDialog(
                shape = RoundedCornerShape(16.dp),
                properties = secondScreenDialogProperties(
                    DialogProperties(usePlatformDefaultWidth = false),
                ),
                icon = icon?.let { { Icon(imageVector = icon, contentDescription = null) } },
                title = title?.let { { Text(it) } },
                text = {
                    Column {
                        if (message != null) {
                            if (useHtmlInMsg) {
                                Text(
                                    text = AnnotatedString.fromHtml(
                                        htmlString = message,
                                        linkStyles = TextLinkStyles(
                                            style = SpanStyle(
                                                textDecoration = TextDecoration.Underline,
                                                fontStyle = FontStyle.Italic,
                                                color = Color.Blue,
                                            ),
                                        ),
                                    ),
                                )
                            } else {
                                Text(message)
                            }
                        }
                        if (shouldShowGamepadUI()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            GamepadHintRow(
                                hints = listOf(
                                    GamepadHint(GamepadButton.A, R.string.action_select),
                                    GamepadHint(GamepadButton.B, R.string.back),
                                ),
                            )
                        }
                    }
                },
                onDismissRequest = { onDismissRequest?.invoke() },
                dismissButton = onDismissClick?.let {
                    {
                        ConsoleDialogButton(
                            text = dismissBtnText,
                            onClick = it,
                            focusRequester = dismissFocusRequester,
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Action button (displayed first if available)
                        if (actionBtnText != null && onActionClick != null) {
                            ConsoleDialogButton(
                                text = actionBtnText,
                                onClick = onActionClick,
                            )
                        }

                        // Confirm button
                        onConfirmClick?.let {
                            ConsoleDialogButton(
                                text = confirmBtnText,
                                onClick = it,
                                focusRequester = confirmFocusRequester,
                                isPrimary = true,
                            )
                        }
                    }
                },
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_MessageDialog() {
    PluviaTheme {
        MessageDialog(
            visible = true,
            icon = Icons.Default.Gamepad,
            title = "Title",
            message = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed " +
                "do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
                "Ut enim ad minim veniam, quis nostrud exercitation ullamco " +
                "laboris nisi ut aliquip ex ea commodo consequat. Duis aute " +
                "irure dolor in reprehenderit in voluptate velit esse cillum " +
                "dolore eu fugiat nulla pariatur. Excepteur sint occaecat " +
                "cupidatat non proident, sunt in culpa qui officia deserunt " +
                "mollit anim id est laborum.",
            onDismissRequest = {},
            onDismissClick = {},
            onConfirmClick = {},
            onActionClick = {},
            actionBtnText = "Action",
        )
    }
}
