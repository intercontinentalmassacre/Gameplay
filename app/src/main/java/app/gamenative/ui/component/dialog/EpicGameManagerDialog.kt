package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.utils.StorageUtils
import com.winlator.core.StringUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpicGameManagerDialog(
    visible: Boolean,
    onGetDisplayInfo: @Composable (Context) -> GameDisplayInfo,
    onInstall: (List<Int>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val allDownloadableGames = remember { mutableStateListOf<EpicGame>() }
    val selectedGameIds = remember { mutableStateMapOf<Int, Boolean>() }

    val displayInfo = onGetDisplayInfo(context)
    val gameId = displayInfo.gameId

    val installOptions = if (visible && !remember(gameId) { EpicService.isGameInstalled(context, gameId) }) {
        rememberInstallContainerOptions(context, "EPIC_$gameId")
    } else null
    val installScope = androidx.compose.runtime.rememberCoroutineScope()
    fun installContent(ids: List<Int>) {
        if (installOptions == null) onInstall(ids)
        else installOptions.install(installScope) { onInstall(ids) }
    }

    LaunchedEffect(visible) {
        scrollState.animateScrollTo(0)

        allDownloadableGames.clear()
        selectedGameIds.clear()

        // Get base game
        val baseGame = EpicService.getEpicGameOf(gameId)
        if (baseGame != null) {
            // Fetch manifest to get accurate sizes
            val sizes = EpicService.fetchManifestSizes(context, baseGame.id ?: 0)
            val updatedGame = baseGame.copy(
                downloadSize = sizes.downloadSize,
                installSize = sizes.installSize
            )
            allDownloadableGames.add(updatedGame)
            // Base game is always selected and can't be deselected
            selectedGameIds[updatedGame.id ?: 0] = true
        }

        // Get DLCs and fetch their manifest sizes
        val dlcs = EpicService.getDLCForGame(gameId)
        dlcs.forEach { dlc ->
            // Fetch manifest to get accurate sizes for each DLC
            val sizes = EpicService.fetchManifestSizes(context, dlc.id ?: 0)
            val updatedDlc = dlc.copy(
                downloadSize = sizes.downloadSize,
                installSize = sizes.installSize
            )
            allDownloadableGames.add(updatedDlc)
            selectedGameIds[updatedDlc.id ?: 0] = true
        }
    }

    fun getInstallSizeInfo(): InstallSizeInfo {
        val installPath = EpicConstants.defaultEpicGamesPath(context)
        val availableBytes = try {
            StorageUtils.getAvailableSpace(installPath)
        } catch (e: Exception) {
            0L
        }

        val selectedGames = allDownloadableGames.filter {
            selectedGameIds[it.id ?: 0] == true
        }

        val totalDownloadBytes = selectedGames.sumOf { it.downloadSize }
        val totalInstallBytes = selectedGames.sumOf { it.installSize }

        return InstallSizeInfo(
            downloadSize = StringUtils.formatBytes(totalDownloadBytes),
            installSize = StringUtils.formatBytes(totalInstallBytes),
            availableSpace = StringUtils.formatBytes(availableBytes),
            installBytes = totalInstallBytes,
            availableBytes = availableBytes
        )
    }

    val installSizeInfo by remember(selectedGameIds.toMap()) {
        derivedStateOf { getInstallSizeInfo() }
    }

    fun installSizeDisplay(): String {
        return context.getString(
            R.string.steam_install_space,
            installSizeInfo.downloadSize,
            installSizeInfo.installSize,
            installSizeInfo.availableSpace
        )
    }

    fun installButtonEnabled(): Boolean {
        // Check if there's enough space
        if (installOptions != null && (!installOptions.ready || installOptions.saving)) return false
        if (installSizeInfo.availableBytes < installSizeInfo.installBytes) {
            return false
        }

        // At least base game should be selected
        return selectedGameIds.filter { it.value }.isNotEmpty()
    }

    SecondScreenRoutedDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
    ) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            ),
        ) {
            InstallPage(
                displayInfo = displayInfo,
                scrollState = scrollState,
                sizeSummary = installSizeDisplay(),
                installEnabled = installButtonEnabled(),
                onBack = onDismissRequest,
                onInstall = { installContent(selectedGameIds.filterValues { it }.keys.toList()) },
            ) {
                installOptions?.let { InstallContainerSection(it) }
                allDownloadableGames.forEach { game ->
                    val gameIdValue = game.id ?: 0
                    val checked = selectedGameIds[gameIdValue] ?: false
                    InstallContentRow(
                        title = game.title,
                        subtitle = stringResource(
                            R.string.install_download_and_install_size,
                            StringUtils.formatBytes(game.downloadSize),
                            StringUtils.formatBytes(game.installSize),
                        ),
                        checked = checked,
                        enabled = gameIdValue != gameId,
                        onToggle = { selectedGameIds[gameIdValue] = !checked },
                        label = if (game.isDLC) "DLC" else null,
                    )
                }
            }
        }
    }
}
