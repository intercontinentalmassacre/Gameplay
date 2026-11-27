package app.gamenative.ui.component.dialog

import android.content.Context
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.SteamService
import app.gamenative.ui.component.IconButton
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CloudSaveBackupManager
import app.gamenative.utils.CloudSaveBackupManager.Kind
import app.gamenative.utils.CloudSaveBackupManager.SaveBackup
import app.gamenative.utils.CustomGameScanner
import com.winlator.container.ContainerManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lists the on-device save backups [CloudSaveBackupManager] keeps for one game and lets the player
 * restore or delete one.
 *
 * - The list is read with [CloudSaveBackupManager.listBackups] (newest first) and re-read after every
 *   action, because a restore writes a fresh `PRE_RESTORE` snapshot and can consume the backup it
 *   restored from.
 * - Restore asks for confirmation first. The confirmation states the two facts the player needs: the
 *   current saves are replaced, and [CloudSaveBackupManager.restore] snapshots them before it writes,
 *   so the restore can itself be undone by restoring that new undo copy.
 * - Both actions are refused while a game container is alive (see [isGameRunning]); the rows are
 *   disabled and a snackbar explains why if the state changes after the dialog was opened.
 * - `deleteBackup` only removes the zip and its sidecar; it never touches the save files themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveBackupRestoreDialog(
    visible: Boolean = true,
    provider: String,
    gameId: String,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return
    if (!rememberHostActivityActive()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val custom = provider == CloudSaveBackupManager.PROVIDER_CUSTOM
    val customPaths = remember(context, custom) {
        if (custom) context.getSharedPreferences("custom_save_paths", Context.MODE_PRIVATE) else null
    }
    var savePath by rememberSaveable(provider, gameId) {
        mutableStateOf(customPaths?.getString(gameId, "").orEmpty())
    }
    var backups by remember(provider, gameId) { mutableStateOf(emptyList<SaveBackup>()) }
    var loading by remember(provider, gameId) { mutableStateOf(true) }
    var busy by remember(provider, gameId) { mutableStateOf(false) }
    var gameRunning by remember(provider, gameId) { mutableStateOf(isGameRunning()) }
    var reloadKey by remember(provider, gameId) { mutableIntStateOf(0) }
    var pendingRestore by remember(provider, gameId) { mutableStateOf<SaveBackup?>(null) }
    var pendingDelete by remember(provider, gameId) { mutableStateOf<SaveBackup?>(null) }
    var pendingExport by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    val gameRunningMessage = stringResource(R.string.save_backup_game_running)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source = pendingExport
        pendingExport = null
        if (source != null && uri != null) {
            busy = true
            scope.launch {
                val exported = CloudSaveBackupManager.exportCustomBackup(context, gameId, source) {
                    context.contentResolver.openOutputStream(uri, "wt")
                }
                busy = false
                SnackbarManager.show(
                    context.getString(if (exported) R.string.save_backup_export_success else R.string.save_backup_export_failed),
                )
            }
        }
    }

    LaunchedEffect(provider, gameId, reloadKey) {
        loading = true
        gameRunning = isGameRunning()
        backups = withContext(Dispatchers.IO) {
            CloudSaveBackupManager.listBackups(context, provider, gameId)
        }
        loading = false
    }

    /** Re-reads the running state so a game started after the dialog opened still blocks the action. */
    fun refuseIfGameRunning(): Boolean {
        val running = isGameRunning()
        gameRunning = running
        if (running) SnackbarManager.show(gameRunningMessage)
        return running
    }

    fun restoreBackup(backup: SaveBackup) {
        if (busy || refuseIfGameRunning()) return
        busy = true
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                CloudSaveBackupManager.restore(context, backup)
            }
            busy = false
            SnackbarManager.show(
                context.getString(
                    if (restored) R.string.save_backup_restore_success else R.string.save_backup_restore_failed,
                ),
            )
            reloadKey++
        }
    }

    fun deleteBackup(backup: SaveBackup) {
        if (busy || refuseIfGameRunning()) return
        busy = true
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                CloudSaveBackupManager.deleteBackup(backup)
            }
            busy = false
            SnackbarManager.show(
                context.getString(
                    if (deleted) R.string.save_backup_delete_success else R.string.save_backup_delete_failed,
                ),
            )
            reloadKey++
        }
    }

    fun createBackup() {
        if (busy || refuseIfGameRunning()) return
        val path = savePath.trim()
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val root = try {
                    val normalized = path.replace('\\', '/')
                    val windows = normalized.startsWith("C:/", ignoreCase = true)
                    val relative = if (windows) normalized.drop(3) else normalized
                    val location = if (windows) {
                        ContainerManager(context).getContainerById(gameId)?.let { File(it.rootDir, ".wine/drive_c") }
                    } else {
                        CustomGameScanner.getFolderPathFromAppId(gameId)?.let(::File)
                    }
                    location?.let { CloudSaveBackupManager.customSaveRoot(it, relative.trimEnd('/')) }
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
                when {
                    root == null -> R.string.save_backup_custom_invalid_path
                    CloudSaveBackupManager.backup(context, provider, gameId, listOf(root), Kind.MANUAL) -> {
                        customPaths?.edit()?.putString(gameId, path)?.apply()
                        R.string.save_backup_custom_success
                    }
                    else -> R.string.save_backup_custom_no_copy
                }
            }
            busy = false
            SnackbarManager.show(context.getString(result))
            reloadKey++
        }
    }

    SecondScreenRoutedDialog(onDismissRequest = onDismissRequest) {
        Dialog(
            onDismissRequest = { if (!busy) onDismissRequest() },
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            ),
            content = {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(text = stringResource(R.string.save_backup_dialog_title)) },
                            navigationIcon = {
                                IconButton(
                                    onClick = onDismissRequest,
                                    enabled = !busy,
                                    content = { Icon(Icons.Default.Close, null) },
                                )
                            },
                        )
                    },
                ) { paddingValues ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                top = app.gamenative.utils.PaddingUtils.statusBarAwarePadding().calculateTopPadding() +
                                    paddingValues.calculateTopPadding(),
                                bottom = 24.dp + paddingValues.calculateBottomPadding(),
                                start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                                end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                            ),
                    ) {
                        if (gameRunning) {
                            item {
                                Text(
                                    text = gameRunningMessage,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        if (custom) {
                            item {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Text(stringResource(R.string.save_backup_custom_path_help))
                                    NoExtractOutlinedTextField(
                                        value = savePath,
                                        onValueChange = { savePath = it },
                                        label = { Text(stringResource(R.string.save_backup_custom_path)) },
                                        enabled = !busy && !gameRunning,
                                        singleLine = true,
                                    )
                                    TextButton(
                                        onClick = ::createBackup,
                                        enabled = !busy && !gameRunning && savePath.isNotBlank(),
                                    ) { Text(stringResource(R.string.save_backup_custom_create)) }
                                }
                            }
                        }

                        if (loading) {
                            item {
                                Text(
                                    text = stringResource(R.string.save_backup_loading),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (backups.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.save_backup_empty),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = backups,
                                key = { _, backup -> backup.file.absolutePath },
                            ) { index, backup ->
                                // Actions are unavailable while a write is in flight and while a game
                                // container is alive; the guard is repeated when the action runs.
                                val actionsEnabled = !busy && !gameRunning
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text(text = formatBackupTime(backup.createdAt)) },
                                    supportingContent = {
                                        Column {
                                            Text(
                                                text = stringResource(
                                                    R.string.save_backup_summary,
                                                    Formatter.formatFileSize(context, backup.sizeBytes),
                                                    backup.fileCount,
                                                ),
                                            )
                                            backup.folder?.let { Text(it) }
                                            if (custom) {
                                                TextButton(
                                                    onClick = {
                                                        pendingExport = backup.file.name
                                                        exportLauncher.launch("saves-${backup.file.name}")
                                                    },
                                                    enabled = !busy && pendingExport == null,
                                                ) {
                                                    Text(stringResource(R.string.option_export_saves))
                                                }
                                            }
                                            if (backup.kind == Kind.PRE_RESTORE) {
                                                Text(
                                                    text = stringResource(R.string.save_backup_kind_pre_restore),
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            TextButton(
                                                onClick = { pendingRestore = backup },
                                                enabled = actionsEnabled,
                                            ) {
                                                Text(text = stringResource(R.string.save_backup_restore))
                                            }
                                            TextButton(
                                                onClick = { pendingDelete = backup },
                                                enabled = actionsEnabled,
                                            ) {
                                                Text(text = stringResource(R.string.save_backup_delete))
                                            }
                                        }
                                    },
                                )
                                if (index < backups.lastIndex) {
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            },
        )

        // Kept inside the routed content so on a dual-screen device the confirmation opens in the
        // same presentation window as the list instead of replacing it.
        pendingRestore?.let { backup ->
            MessageDialog(
                visible = true,
                title = stringResource(R.string.save_backup_restore_confirm_title),
                message = stringResource(R.string.save_backup_restore_confirm_message),
                confirmBtnText = stringResource(R.string.save_backup_restore),
                dismissBtnText = stringResource(R.string.cancel),
                onDismissRequest = { pendingRestore = null },
                onDismissClick = { pendingRestore = null },
                onConfirmClick = {
                    pendingRestore = null
                    restoreBackup(backup)
                },
            )
        }

        pendingDelete?.let { backup ->
            MessageDialog(
                visible = true,
                title = stringResource(R.string.save_backup_delete_confirm_title),
                message = stringResource(R.string.save_backup_delete_confirm_message),
                confirmBtnText = stringResource(R.string.save_backup_delete),
                dismissBtnText = stringResource(R.string.cancel),
                onDismissRequest = { pendingDelete = null },
                onDismissClick = { pendingDelete = null },
                onConfirmClick = {
                    pendingDelete = null
                    deleteBackup(backup)
                },
            )
        }
    }
}

/**
 * The local "a game container is alive" signals, in the order the rest of the app already uses them:
 * the shared X environment (every store runs in it) plus the Steam session flags. GOGService and
 * EpicService `isRunning` are service-process flags, not game-session flags, so they are deliberately
 * not part of this check.
 */
private fun isGameRunning(): Boolean =
    PluviaApp.xEnvironment != null || SteamService.keepAlive || ActiveGameRegistry.get() != null

private fun formatBackupTime(createdAt: Long): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(createdAt))
