package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.theme.PluviaTheme

/** Context-sensitive library menu opened by the controller B/Circle button. */
@Composable
fun LibraryQuickActionsPanel(
    isOpen: Boolean,
    focusedItem: LibraryItem?,
    onDismiss: () -> Unit,
    onPrimaryAction: (LibraryItem) -> Unit,
    onDetails: (LibraryItem) -> Unit,
    onContainerSettings: (LibraryItem) -> Unit,
    onAchievements: (LibraryItem) -> Unit,
    onAddToHome: (LibraryItem) -> Unit = {},
    onLibraryOptions: () -> Unit,
    onSearch: () -> Unit,
    onAddGame: () -> Unit,
) {
    ConsoleSidePanel(
        isOpen = isOpen,
        onDismiss = onDismiss,
        focusRequestKey = focusedItem?.appId,
        verticalArrangement = Arrangement.spacedBy(PluviaTheme.tokens.menuItemGap),
    ) { firstItemFocusRequester ->
        ConsolePanelHeader(
            title = stringResource(R.string.quick_menu_title),
            onBack = onDismiss,
        )
        focusedItem?.let { item ->
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (!item.isRecommended) {
                val canRunImmediately = item.isInstalled || item.gameSource == GameSource.CUSTOM_GAME
                ConsoleMenuActionItem(
                    icon = if (canRunImmediately) Icons.Default.PlayArrow else Icons.Default.Download,
                    label = stringResource(if (canRunImmediately) R.string.run_app else R.string.install_app),
                    onClick = { onPrimaryAction(item) },
                    modifier = Modifier.focusRequester(firstItemFocusRequester),
                    emphasized = true,
                )
            }
            ConsoleMenuActionItem(
                icon = Icons.Default.Info,
                label = stringResource(R.string.action_details),
                onClick = { onDetails(item) },
                modifier = if (item.isRecommended) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
            )
            ConsoleMenuActionItem(
                icon = Icons.Default.Settings,
                label = stringResource(R.string.quick_action_container_settings),
                onClick = { onContainerSettings(item) },
            )
            if (item.gameSource == GameSource.STEAM) {
                ConsoleMenuActionItem(
                    icon = Icons.Default.EmojiEvents,
                    label = stringResource(R.string.achievements),
                    onClick = { onAchievements(item) },
                )
            }
            if (!item.isRecommended) {
                ConsoleMenuActionItem(
                    icon = Icons.Default.AddToHomeScreen,
                    label = stringResource(R.string.action_add_to_home),
                    onClick = { onAddToHome(item) },
                )
            }
        }

        ConsoleMenuActionItem(
            icon = Icons.Default.Tune,
            label = stringResource(R.string.options),
            onClick = onLibraryOptions,
            modifier = if (focusedItem == null) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
        )
        ConsoleMenuActionItem(
            icon = Icons.Default.Search,
            label = stringResource(R.string.search),
            onClick = onSearch,
        )
        if (focusedItem == null) {
            ConsoleMenuActionItem(
                icon = Icons.Default.Add,
                label = stringResource(R.string.action_add_game),
                onClick = onAddGame,
            )
        }
        ConsolePanelBackHint()
    }
}
