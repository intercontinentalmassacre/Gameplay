package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.ItchGame
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.itch.ItchAccess
import app.gamenative.itch.ItchDownloadStart
import app.gamenative.itch.ItchExecutableCandidate
import app.gamenative.itch.ItchInstalledRevision
import app.gamenative.itch.ItchUploadCandidate
import app.gamenative.itch.ItchUpdateStatus
import app.gamenative.ui.component.dialog.MessageDialog
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.enums.DialogType
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.StorageUtils
import com.winlator.container.ContainerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * itch.io [BaseAppScreen] implementation.
 *
 * Main action follows the state: Download → (optional build picker) → Continue /
 * Install (EXE installer) → Play. A separate Update action is shown when a new
 * build of the *same* upload is available, so the current version stays playable.
 */
class ItchAppScreen : BaseAppScreen() {

    companion object {
        private const val TAG = "ItchAppScreen"
        private const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        internal const val EXTENSION = ".itch"

        sealed interface ItchDialog {
            data class BuildPicker(
                val title: String,
                val uploads: List<ItchUploadCandidate>,
                val onPick: (Long) -> Unit,
            ) : ItchDialog

            data class ExecutablePicker(
                val title: String,
                val candidates: List<ItchExecutableCandidate>,
                val onPick: (String) -> Unit,
            ) : ItchDialog

            data class Confirm(
                val title: String,
                val message: String,
                val confirmLabel: String,
                val onConfirm: () -> Unit,
            ) : ItchDialog

            data class Notice(
                val title: String,
                val message: String,
                val confirmLabel: String?,
                val onConfirm: (() -> Unit)?,
            ) : ItchDialog
        }

        private val dialogs = mutableStateMapOf<String, ItchDialog>()

        private fun showDialog(appId: String, dialog: ItchDialog) {
            dialogs[appId] = dialog
        }

        private fun hideDialog(appId: String) {
            dialogs.remove(appId)
        }

        internal fun dialogFor(appId: String): ItchDialog? = dialogs[appId]
    }

    // ── Display info ────────────────────────────────────────────────────────

    @Composable
    override fun getGameDisplayInfo(
        context: Context,
        libraryItem: LibraryItem,
    ): GameDisplayInfo {
        val gameId = libraryItem.gameId.toLong()
        var game by remember(libraryItem.appId) { mutableStateOf<ItchGame?>(null) }
        var refreshKey by remember(libraryItem.appId) { mutableIntStateOf(0) }

        LaunchedEffect(libraryItem.appId, refreshKey) {
            val installer = ItchAccess.installer(context)
            // A prepared installer survives process restarts; reflect it in the label.
            val session = runCatching {
                withContext(Dispatchers.IO) { installer.installerSession(gameId) }
            }.getOrNull()
            if (session != null) installer.markInstallerPrepared(gameId)
            game = withContext(Dispatchers.IO) {
                runCatching { ItchAccess.repository(context).getGame(gameId) }.getOrNull()
            }
        }
        DisposableEffect(libraryItem.appId) {
            val listener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
                if (event.source == GameSource.ITCH &&
                    (event.appId == libraryItem.gameId || event.appId == gameId.toInt())
                ) {
                    refreshKey++
                }
            }
            PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            onDispose {
                PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(listener)
            }
        }

        val current = game
        val name = current?.title?.takeIf(String::isNotBlank) ?: libraryItem.name
        val cover = current?.coverUrl?.takeIf(String::isNotBlank)
            ?: libraryItem.capsuleImageUrl.takeIf(String::isNotBlank)
        val (compatibilityMessage, compatibilityColor) = rememberCompatibilityInfo(
            context = context,
            gameName = name,
        )
        val sizeOnDisk = current?.sizeOnDisk
            ?.takeIf { it > 0L }
            ?.let { StorageUtils.formatBinarySize(it) }

        return GameDisplayInfo(
            name = name,
            iconUrl = cover,
            heroImageUrl = cover,
            gameId = libraryItem.gameId,
            appId = libraryItem.appId,
            releaseDate = 0L,
            developer = current?.author.orEmpty(),
            installLocation = current?.installRoot?.takeIf(String::isNotBlank),
            sizeOnDisk = sizeOnDisk,
            sizeFromStore = null,
            lastPlayedText = null,
            playtimeText = null,
            compatibilityMessage = compatibilityMessage,
            compatibilityColor = compatibilityColor,
            capsuleUrl = cover,
            headerUrl = cover,
        )
    }

    // ── State ───────────────────────────────────────────────────────────────

    override fun isInstalled(context: Context, libraryItem: LibraryItem): Boolean =
        app.gamenative.itch.ItchGameCache.installed(libraryItem.gameId.toLong())

    override fun isValidToDownload(context: Context, libraryItem: LibraryItem): Boolean =
        !isInstalled(context, libraryItem) && !isDownloading(context, libraryItem)

    override fun isDownloading(context: Context, libraryItem: LibraryItem): Boolean {
        val manager = runCatching { ItchAccess.downloads(context) }.getOrNull() ?: return false
        return manager.isDownloading(libraryItem.gameId.toLong())
    }

    override fun getDownloadProgress(context: Context, libraryItem: LibraryItem): Float {
        val manager = runCatching { ItchAccess.downloads(context) }.getOrNull() ?: return 0f
        return manager.getDownloadInfo(libraryItem.gameId.toLong())?.getProgress() ?: 0f
    }

    override fun hasPartialDownload(context: Context, libraryItem: LibraryItem): Boolean =
        runCatching { ItchAccess.downloads(context).hasPartialDownload(libraryItem.gameId.toLong()) }
            .getOrDefault(false)

    override suspend fun isUpdatePendingSuspend(context: Context, libraryItem: LibraryItem): Boolean {
        val gameId = libraryItem.gameId.toLong()
        val repository = ItchAccess.repository(context)
        val game = repository.getGame(gameId) ?: return false
        if (!game.isInstalled) return false
        if (game.updateStatus == ItchGame.UPDATE_STATUS_AVAILABLE) return true
        // Re-check at most every ten minutes, and only report "update" on evidence.
        if (System.currentTimeMillis() - game.updateCheckedAtMs < UPDATE_CHECK_INTERVAL_MS) return false
        if (!ItchAccess.auth(context).hasStoredCredentials()) return false
        val decision = repository.checkForUpdate(gameId).getOrNull() ?: return false
        return decision.status == ItchUpdateStatus.UPDATE_AVAILABLE
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    override fun onDownloadInstallClick(
        context: Context,
        libraryItem: LibraryItem,
        onClickPlay: (Boolean) -> Unit,
    ) {
        val gameId = libraryItem.gameId.toLong()
        CoroutineScope(Dispatchers.IO).launch {
            val game = runCatching { ItchAccess.repository(context).getGame(gameId) }.getOrNull()
            if (game?.isInstalled == true) {
                withContext(Dispatchers.Main) { onClickPlay(false) }
                return@launch
            }
            // A downloaded Windows installer waits for its run: launch the container.
            val session = runCatching { ItchAccess.installer(context).installerSession(gameId) }.getOrNull()
            if (session != null) {
                withContext(Dispatchers.Main) { onClickPlay(false) }
                return@launch
            }
            startDownload(context, libraryItem, gameId)
        }
    }

    override fun onPauseResumeClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId.toLong()
        val manager = ItchAccess.downloads(context)
        if (manager.isDownloading(gameId)) {
            manager.stop(gameId, deletePartial = false)
        } else {
            CoroutineScope(Dispatchers.IO).launch { startDownload(context, libraryItem, gameId) }
        }
    }

    override fun onDeleteDownloadClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId.toLong()
        val manager = runCatching { ItchAccess.downloads(context) }.getOrNull()
        val downloading = manager?.isDownloading(gameId) == true
        val partial = manager?.hasPartialDownload(gameId) == true
        val appId = libraryItem.appId
        if (downloading || partial) {
            showDialog(
                appId,
                ItchDialog.Confirm(
                    title = context.getString(R.string.itch_cancel_download_title),
                    message = context.getString(R.string.itch_cancel_download_message),
                    confirmLabel = context.getString(R.string.yes),
                    onConfirm = {
                        hideDialog(appId)
                        manager?.stop(gameId, deletePartial = true)
                    },
                ),
            )
        } else if (isInstalled(context, libraryItem)) {
            showDialog(
                appId,
                ItchDialog.Confirm(
                    title = context.getString(R.string.itch_uninstall_title),
                    message = context.getString(R.string.itch_uninstall_message, libraryItem.name),
                    confirmLabel = context.getString(R.string.uninstall),
                    onConfirm = {
                        hideDialog(appId)
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = ItchAccess.installer(context).uninstall(gameId)
                            withContext(Dispatchers.Main) {
                                if (result.isFailure) {
                                    SnackbarManager.show(
                                        context.getString(
                                            R.string.itch_install_failed,
                                            result.exceptionOrNull()?.message.orEmpty(),
                                        ),
                                    )
                                }
                            }
                            PluviaApp.events.emitJava(
                                AndroidEvent.LibraryInstallStatusChanged(gameId.toInt(), GameSource.ITCH),
                            )
                        }
                    },
                ),
            )
        }
    }

    override fun onUpdateClick(context: Context, libraryItem: LibraryItem) {
        val gameId = libraryItem.gameId.toLong()
        val appId = libraryItem.appId
        CoroutineScope(Dispatchers.IO).launch {
            val repository = ItchAccess.repository(context)
            val decision = repository.checkForUpdate(gameId).getOrElse { error ->
                withContext(Dispatchers.Main) {
                    SnackbarManager.show(
                        context.getString(R.string.itch_update_check_failed, error.message.orEmpty()),
                    )
                }
                return@launch
            }
            val uploadId = decision.upload?.id
            when {
                decision.manualInstaller && decision.status == ItchUpdateStatus.UPDATE_AVAILABLE -> {
                    withContext(Dispatchers.Main) {
                        showDialog(
                            appId,
                            ItchDialog.Confirm(
                                title = context.getString(R.string.itch_installer_update_title),
                                message = context.getString(R.string.itch_installer_update_message),
                                confirmLabel = context.getString(R.string.itch_installer_update_confirm),
                                onConfirm = {
                                    hideDialog(appId)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        startDownload(context, libraryItem, gameId, uploadId)
                                    }
                                },
                            ),
                        )
                    }
                }
                decision.status == ItchUpdateStatus.VERSION_UNKNOWN ||
                    decision.status == ItchUpdateStatus.UPLOAD_MISSING -> {
                    withContext(Dispatchers.Main) {
                        showDialog(
                            appId,
                            ItchDialog.Confirm(
                                title = context.getString(R.string.itch_version_unknown_title),
                                message = context.getString(R.string.itch_version_unknown_message),
                                confirmLabel = context.getString(R.string.itch_reinstall),
                                onConfirm = {
                                    hideDialog(appId)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        startDownload(context, libraryItem, gameId, uploadId)
                                    }
                                },
                            ),
                        )
                    }
                }
                decision.status == ItchUpdateStatus.UP_TO_DATE -> withContext(Dispatchers.Main) {
                    SnackbarManager.show(context.getString(R.string.itch_up_to_date))
                }
                decision.status == ItchUpdateStatus.UPDATE_AVAILABLE -> {
                    startDownload(context, libraryItem, gameId, uploadId)
                }
                else -> withContext(Dispatchers.Main) {
                    SnackbarManager.show(context.getString(R.string.itch_update_check_unavailable))
                }
            }
        }
    }

    private suspend fun startDownload(
        context: Context,
        libraryItem: LibraryItem,
        gameId: Long,
        uploadId: Long? = null,
    ) {
        val appId = libraryItem.appId
        when (val result = ItchAccess.downloads(context).start(gameId, uploadId)) {
            is ItchDownloadStart.Started -> withContext(Dispatchers.Main) {
                SnackbarManager.show(
                    context.getString(R.string.itch_download_started, result.upload.title),
                )
            }
            is ItchDownloadStart.AlreadyActive -> Unit
            is ItchDownloadStart.NeedsUploadSelection -> withContext(Dispatchers.Main) {
                showDialog(
                    appId,
                    ItchDialog.BuildPicker(
                        title = context.getString(R.string.itch_choose_build_title),
                        uploads = result.selection.choice.let { choice ->
                            (choice as? app.gamenative.itch.ItchUploadChoice.Multiple)?.uploads.orEmpty()
                        },
                        onPick = { chosen ->
                            hideDialog(appId)
                            CoroutineScope(Dispatchers.IO).launch {
                                startDownload(context, libraryItem, gameId, chosen)
                            }
                        },
                    ),
                )
            }
            is ItchDownloadStart.OpenInBrowser -> withContext(Dispatchers.Main) {
                SnackbarManager.show(context.getString(R.string.itch_no_windows_build))
                openInBrowser(context, result.url)
            }
            is ItchDownloadStart.Failed -> withContext(Dispatchers.Main) {
                SnackbarManager.show(context.getString(R.string.itch_download_failed, result.message))
            }
        }
    }

    override fun getExportFileExtension(): String = EXTENSION

    override fun primaryActionLabel(
        context: Context,
        libraryItem: LibraryItem,
    ): ((Boolean, Boolean, Boolean) -> String?)? {
        val gameId = libraryItem.gameId.toLong()
        return { isInstalled, isDownloading, hasPartialDownload ->
            when {
                isInstalled -> context.getString(R.string.run_app)
                // While a transfer is running the shared label is "Pause".
                isDownloading -> null
                hasPartialDownload -> context.getString(R.string.resume_download)
                app.gamenative.itch.ItchPaths.hasPendingInstaller(context, gameId) ->
                    context.getString(R.string.itch_install)
                else -> context.getString(R.string.itch_download)
            }
        }
    }

    override fun getInstallPath(context: Context, libraryItem: LibraryItem): String? =
        app.gamenative.itch.ItchGameCache.get(libraryItem.gameId.toLong())
            ?.installRoot
            ?.takeIf(String::isNotBlank)

    // ── Menu ────────────────────────────────────────────────────────────────

    @Composable
    override fun getSourceSpecificMenuOptions(
        context: Context,
        libraryItem: LibraryItem,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
        onClickPlay: (Boolean) -> Unit,
        isInstalled: Boolean,
    ): List<AppMenuOption> {
        val appId = libraryItem.appId
        val gameId = libraryItem.gameId.toLong()
        val options = mutableListOf<AppMenuOption>()
        val game = remember(appId) { mutableStateOf<ItchGame?>(null) }
        LaunchedEffect(appId) {
            game.value = withContext(Dispatchers.IO) {
                runCatching { ItchAccess.repository(context).getGame(gameId) }.getOrNull()
            }
        }

        options += AppMenuOption(
            optionType = AppOptionMenuType.ItchCheckForUpdates,
            onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    val repository = ItchAccess.repository(context)
                    val decision = repository.checkForUpdate(gameId).getOrNull()
                    val uploadId = decision?.upload?.id
                    withContext(Dispatchers.Main) {
                        when (decision?.status) {
                            ItchUpdateStatus.UP_TO_DATE -> showDialog(
                                appId,
                                ItchDialog.Notice(
                                    title = context.getString(R.string.itch_check_updates),
                                    message = context.getString(R.string.itch_up_to_date),
                                    confirmLabel = null,
                                    onConfirm = null,
                                ),
                            )

                            ItchUpdateStatus.UPDATE_AVAILABLE -> showDialog(
                                appId,
                                ItchDialog.Notice(
                                    title = context.getString(R.string.itch_check_updates),
                                    message = context.getString(R.string.itch_update_available),
                                    confirmLabel = context.getString(R.string.option_update),
                                    onConfirm = {
                                        hideDialog(appId)
                                        onUpdateClick(context, libraryItem)
                                    },
                                ),
                            )

                            ItchUpdateStatus.VERSION_UNKNOWN,
                            ItchUpdateStatus.UPLOAD_MISSING,
                            -> showDialog(
                                appId,
                                ItchDialog.Confirm(
                                    title = context.getString(R.string.itch_version_unknown_title),
                                    message = context.getString(R.string.itch_version_unknown_message),
                                    confirmLabel = context.getString(R.string.itch_reinstall),
                                    onConfirm = {
                                        hideDialog(appId)
                                        CoroutineScope(Dispatchers.IO).launch {
                                            startDownload(context, libraryItem, gameId, uploadId)
                                        }
                                    },
                                ),
                            )

                            else -> showDialog(
                                appId,
                                ItchDialog.Notice(
                                    title = context.getString(R.string.itch_check_updates),
                                    message = context.getString(R.string.itch_update_check_unavailable),
                                    confirmLabel = null,
                                    onConfirm = null,
                                ),
                            )
                        }
                    }
                }
            },
        )

        if (isInstalled && game.value?.installKind == ItchGame.INSTALL_KIND_ZIP) {
            options += AppMenuOption(
                optionType = AppOptionMenuType.ItchChangeExecutable,
                onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                        val candidates = ItchAccess.installer(context).executableCandidates(gameId)
                        withContext(Dispatchers.Main) {
                            if (candidates.isEmpty()) {
                                SnackbarManager.show(context.getString(R.string.itch_no_executables))
                            } else {
                                showDialog(
                                    appId,
                                    ItchDialog.ExecutablePicker(
                                        title = context.getString(R.string.itch_choose_executable_title),
                                        candidates = candidates,
                                        onPick = { target ->
                                            hideDialog(appId)
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val result = ItchAccess.installer(context).changeLaunchTarget(gameId, target)
                                                withContext(Dispatchers.Main) {
                                                    if (result.isFailure) {
                                                        SnackbarManager.show(
                                                            context.getString(
                                                                R.string.itch_install_failed,
                                                                result.exceptionOrNull()?.message.orEmpty(),
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    ),
                                )
                            }
                        }
                    }
                },
            )
        }

        options += AppMenuOption(
            optionType = AppOptionMenuType.ItchOpenOnSite,
            onClick = {
                openInBrowser(context, game.value?.url ?: "https://itch.io")
            },
        )
        options += AppMenuOption(
            optionType = AppOptionMenuType.ItchSignOut,
            onClick = {
                showDialog(
                    appId,
                    ItchDialog.Confirm(
                        title = context.getString(R.string.itch_sign_out_title),
                        message = context.getString(R.string.itch_sign_out_message),
                        confirmLabel = context.getString(R.string.itch_sign_out),
                        onConfirm = {
                            hideDialog(appId)
                            app.gamenative.itch.ItchAuthUi.signOut(context)
                        },
                    ),
                )
            },
        )
        return options
    }

    @Composable
    override fun getResetContainerOption(
        context: Context,
        libraryItem: LibraryItem,
    ): AppMenuOption? {
        var showDialog by remember { mutableStateOf(false) }
        if (showDialog) {
            ResetConfirmDialog(
                onConfirm = {
                    showDialog = false
                    resetContainerToDefaults(context, libraryItem)
                },
                onDismiss = { showDialog = false },
            )
        }
        return AppMenuOption(
            optionType = AppOptionMenuType.ResetToDefaults,
            onClick = { showDialog = true },
        )
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    @Composable
    override fun AdditionalDialogs(
        libraryItem: LibraryItem,
        onDismiss: () -> Unit,
        onEditContainer: () -> Unit,
        onBack: () -> Unit,
    ) {
        val appId = libraryItem.appId
        val dialog = dialogFor(appId) ?: return

        when (dialog) {
            is ItchDialog.BuildPicker -> AlertDialog(
                onDismissRequest = { hideDialog(appId) },
                title = { Text(dialog.title) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dialog.uploads.forEach { upload ->
                            TextButton(
                                onClick = { dialog.onPick(upload.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(upload.title, style = MaterialTheme.typography.bodyLarge)
                                    val details = buildList {
                                        upload.versionLabel?.let { add(stringResource(R.string.itch_version_label, it)) }
                                        if (upload.sizeLabel.isNotBlank()) add(upload.sizeLabel)
                                        add(roleLabel(upload.role))
                                    }.joinToString(" · ")
                                    Text(details, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { hideDialog(appId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )

            is ItchDialog.ExecutablePicker -> AlertDialog(
                onDismissRequest = { hideDialog(appId) },
                title = { Text(dialog.title) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dialog.candidates.forEach { candidate ->
                            TextButton(
                                onClick = { dialog.onPick(candidate.path) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(candidate.path, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { hideDialog(appId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )

            is ItchDialog.Confirm -> AlertDialog(
                onDismissRequest = { hideDialog(appId) },
                title = { Text(dialog.title) },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(onClick = dialog.onConfirm) { Text(dialog.confirmLabel) }
                },
                dismissButton = {
                    TextButton(onClick = { hideDialog(appId) }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )

            is ItchDialog.Notice -> AlertDialog(
                onDismissRequest = { hideDialog(appId) },
                title = { Text(dialog.title) },
                text = { Text(dialog.message) },
                confirmButton = {
                    val action = dialog.onConfirm
                    if (action != null) {
                        TextButton(onClick = action) {
                            Text(dialog.confirmLabel ?: stringResource(R.string.ok))
                        }
                    } else {
                        TextButton(onClick = { hideDialog(appId) }) {
                            Text(stringResource(R.string.ok))
                        }
                    }
                },
                dismissButton = if (dialog.onConfirm != null) {
                    { TextButton(onClick = { hideDialog(appId) }) { Text(stringResource(R.string.cancel)) } }
                } else {
                    null
                },
            )
        }
    }

    // ── Observation / container ─────────────────────────────────────────────

    override fun observeGameState(
        context: Context,
        libraryItem: LibraryItem,
        onStateChanged: () -> Unit,
        onProgressChanged: (Float) -> Unit,
        onHasPartialDownloadChanged: ((Boolean) -> Unit)?,
    ): (() -> Unit)? {
        val gameId = libraryItem.gameId.toLong()
        val disposables = mutableListOf<() -> Unit>()
        val manager = runCatching { ItchAccess.downloads(context) }.getOrNull()

        val progressListener: (Float) -> Unit = { onProgressChanged(it) }
        val info = manager?.getDownloadInfo(gameId)
        if (info != null) {
            info.addProgressListener(progressListener)
            disposables += { info.removeProgressListener(progressListener) }
        }

        val downloadListener: (AndroidEvent.DownloadStatusChanged) -> Unit = { event ->
            if (event.appId == gameId.toInt()) {
                if (event.isDownloading) {
                    manager?.getDownloadInfo(gameId)?.let { active ->
                        active.addProgressListener(progressListener)
                        onProgressChanged(active.getProgress())
                    }
                } else {
                    onHasPartialDownloadChanged?.invoke(manager?.hasPartialDownload(gameId) == true)
                }
                onStateChanged()
            }
        }
        PluviaApp.events.on<AndroidEvent.DownloadStatusChanged, Unit>(downloadListener)
        disposables += {
            PluviaApp.events.off<AndroidEvent.DownloadStatusChanged, Unit>(downloadListener)
        }

        val installListener: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = { event ->
            if (event.source == GameSource.ITCH && event.appId == gameId.toInt()) {
                onStateChanged()
            }
        }
        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        disposables += {
            PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(installListener)
        }

        return { disposables.forEach { it() } }
    }

    override fun loadContainerData(context: Context, libraryItem: LibraryItem): ContainerData {
        val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
        return ContainerUtils.toContainerData(container)
    }

    override fun saveContainerConfig(
        context: Context,
        libraryItem: LibraryItem,
        config: ContainerData,
    ) {
        ContainerUtils.applyToContainer(context, libraryItem.appId, config)
    }

    override fun supportsContainerConfig(): Boolean = true

    private fun openInBrowser(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (context !is android.app.Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Could not open $url")
            SnackbarManager.show(context.getString(R.string.itch_open_site_failed))
        }
    }

    @Composable
    private fun roleLabel(role: app.gamenative.itch.ItchUploadRole): String = stringResource(
        when (role) {
            app.gamenative.itch.ItchUploadRole.GAME -> R.string.itch_role_game
            app.gamenative.itch.ItchUploadRole.DEMO -> R.string.itch_role_demo
            app.gamenative.itch.ItchUploadRole.EDITOR -> R.string.itch_role_editor
            app.gamenative.itch.ItchUploadRole.MANUAL -> R.string.itch_role_manual
            app.gamenative.itch.ItchUploadRole.SOUNDTRACK -> R.string.itch_role_soundtrack
            app.gamenative.itch.ItchUploadRole.ART -> R.string.itch_role_art
            app.gamenative.itch.ItchUploadRole.OTHER -> R.string.itch_role_other
        },
    )
}

