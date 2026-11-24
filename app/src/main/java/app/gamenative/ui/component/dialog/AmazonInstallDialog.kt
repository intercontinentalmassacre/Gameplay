package app.gamenative.ui.component.dialog

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.data.GameDisplayInfo

/**
 * Full-screen install confirmation dialog for Amazon games.
 *
 * Mirrors the layout of [GameManagerDialog] used for Steam games so both platforms
 * present a consistent pre-install experience.
 */
@Composable
fun AmazonInstallDialog(
    visible: Boolean,
    displayInfo: GameDisplayInfo,
    downloadSize: String,
    installSize: String,
    availableSpace: String,
    installEnabled: Boolean,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val sizeDisplay = stringResource(
        R.string.steam_install_space,
        downloadSize,
        installSize,
        availableSpace,
    )

    SecondScreenRoutedDialog(onDismissRequest = onDismiss) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            ),
        ) {
            InstallPage(
                displayInfo = displayInfo,
                scrollState = rememberScrollState(),
                sizeSummary = sizeDisplay,
                installEnabled = installEnabled,
                onInstall = onInstall,
                onBack = onDismiss,
                showCancel = true,
            ) {
                InstallContentRow(
                    title = displayInfo.name,
                    subtitle = stringResource(R.string.install_download_and_install_size, downloadSize, installSize),
                    checked = true,
                    enabled = false,
                    onToggle = {},
                )
            }
        }
    }
}
