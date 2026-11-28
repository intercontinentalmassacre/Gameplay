package app.gamenative.ui.screen.library.appscreen

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.localgames.BuranGameLaunch
import app.gamenative.localgames.BuranGameLaunch.Runtime
import app.gamenative.localgames.BuranInstalledProton
import app.gamenative.ui.component.dialog.SingleChoiceDialog
import app.gamenative.ui.component.settings.SettingsSwitch
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun gameRuntimeOption(context: Context, appId: String): AppMenuOption? {
    if (!BuranGameLaunch.isAvailable()) return null
    var showDialog by remember(appId) { mutableStateOf(false) }
    var selection by remember(appId) { mutableStateOf(BuranGameLaunch.selection(context, appId)) }
    if (showDialog) {
        var wineDesktop by remember(appId, selection) {
            mutableStateOf(BuranGameLaunch.wineDesktop(context, appId, selection))
        }
        val capabilities by produceState<RuntimeCapabilities?>(null, appId) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val profile = ContainerManager(context).getContainerById(appId)
                    RuntimeCapabilities(
                        bundled = BuranGameLaunch.playIntent(context, appId, profile, Runtime.BUNDLED) != null,
                        arm64Ec = BuranGameLaunch.playIntent(context, appId, profile, Runtime.INSTALLED_PROTON) != null,
                        proton = BuranInstalledProton.configuredVersion(profile),
                    )
                }.getOrDefault(RuntimeCapabilities(false, false, null))
            }
        }
        val isSteam = ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.STEAM
        val options = buildList {
            add(Runtime.WINLATOR)
            if (capabilities?.bundled == true || selection == Runtime.BUNDLED) add(Runtime.BUNDLED)
            if (isSteam && (capabilities?.arm64Ec == true || selection == Runtime.STEAM_CLIENT)) add(Runtime.STEAM_CLIENT)
            if (selection == Runtime.INSTALLED_PROTON || capabilities?.arm64Ec == true) {
                add(Runtime.INSTALLED_PROTON)
            }
        }
        val labels = options.map {
            when (it) {
                Runtime.WINLATOR -> stringResource(R.string.local_runtime_default)
                Runtime.BUNDLED -> stringResource(R.string.local_runtime_buran)
                Runtime.STEAM_CLIENT -> stringResource(R.string.steam_runtime_buran_client)
                Runtime.INSTALLED_PROTON -> capabilities?.proton?.let { proton ->
                    stringResource(R.string.local_runtime_buran_proton_version, proton)
                } ?: stringResource(R.string.local_runtime_buran_proton)
            }
        }
        SingleChoiceDialog(
            openDialog = true,
            title = stringResource(R.string.local_runtime_title),
            items = labels,
            currentItem = options.indexOf(selection).coerceAtLeast(0),
            onSelected = { index ->
                selection = options[index]
                BuranGameLaunch.select(context, appId, selection)
                if (selection == Runtime.WINLATOR) showDialog = false
            },
            onDismiss = { showDialog = false },
            itemDescription = { index ->
                when (options[index]) {
                    Runtime.WINLATOR -> if (capabilities?.arm64Ec == false) {
                        stringResource(R.string.local_runtime_buran_incompatible)
                    } else null
                    Runtime.STEAM_CLIENT -> stringResource(R.string.steam_runtime_buran_client_description)
                    Runtime.INSTALLED_PROTON -> stringResource(
                        if (capabilities != null && capabilities?.proton == null) R.string.buran_proton_incompatible
                        else R.string.local_runtime_buran_proton_description,
                    )
                    Runtime.BUNDLED -> stringResource(
                        if (isSteam) R.string.steam_runtime_buran_description else R.string.local_runtime_buran_description,
                    )
                }
            },
            additionalContent = {
                if (selection != Runtime.WINLATOR) {
                    SettingsSwitch(
                        state = wineDesktop,
                        title = { Text(stringResource(R.string.buran_wine_desktop)) },
                        onCheckedChange = { enabled ->
                            wineDesktop = enabled
                            BuranGameLaunch.setWineDesktop(context, appId, enabled)
                        },
                    )
                }
            },
        )
    }
    return AppMenuOption(AppOptionMenuType.GameRuntime) { showDialog = true }
}

private data class RuntimeCapabilities(val bundled: Boolean, val arm64Ec: Boolean, val proton: String?)
