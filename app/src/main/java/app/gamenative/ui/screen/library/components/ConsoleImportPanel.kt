package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme

/** Controller-first replacement for the mobile add-game dialog. */
@Composable
fun ConsoleImportPanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onImportExecutable: () -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsoleSidePanel(
        isOpen = isOpen,
        onDismiss = onDismiss,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(PluviaTheme.tokens.menuItemGap),
    ) { firstItemFocusRequester ->
        ConsolePanelHeader(
            title = stringResource(R.string.add_custom_game_dialog_title),
            onBack = onDismiss,
        )
        Text(
            text = stringResource(R.string.add_custom_game_dialog_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ConsoleMenuActionItem(
            icon = Icons.Default.Download,
            label = stringResource(R.string.add_custom_game_install),
            onClick = onInstall,
            modifier = Modifier.focusRequester(firstItemFocusRequester),
        )
        ConsoleMenuActionItem(
            icon = Icons.Default.Add,
            label = stringResource(R.string.add_custom_game_import_exe),
            onClick = onImportExecutable,
        )
        ConsoleMenuActionItem(
            icon = Icons.Default.Folder,
            label = stringResource(R.string.add_custom_game_choose_folder),
            onClick = onChooseFolder,
        )
        ConsolePanelBackHint()
    }
}
