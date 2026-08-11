package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import app.gamenative.ui.component.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.utils.rememberHasExternalDisplay

/**
 * A settings destination presented as a complete console screen instead of a floating mobile modal.
 */
@Composable
fun ConsoleSettingsPage(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    embedded: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (!embedded && hasExternalDisplay && !alreadyOnSecondScreen) {
        val secondScreenContent: @Composable () -> Unit = {
            ConsoleSettingsPage(
                visible = true,
                title = title,
                onDismissRequest = onDismissRequest,
                actions = actions,
                embedded = true,
                content = content,
            )
        }
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = onDismissRequest,
                    settingsContent = secondScreenContent,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    val pageContent: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        actions()
                    }
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                Box(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    if (embedded) {
        pageContent()
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                    decorFitsSystemWindows = false,
                ),
            ),
            content = pageContent,
        )
    }
}
