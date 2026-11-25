package app.gamenative.ui.component.dialog

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.component.settings.SettingsSwitch
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.KeyValueSet
import com.winlator.core.WineInfo
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class InstallContainerOptions(private val context: Context, private val appId: String) {
    var preset by mutableStateOf<CompatPreset?>(null)
    var dxvk by mutableStateOf<String?>(null)
    var versions by mutableStateOf(emptyList<String>())
    var shared by mutableStateOf(PrefManager.sharedContainerBase)
    var canChooseStorage by mutableStateOf(false)
    var windows by mutableStateOf(true)
    var ready by mutableStateOf(false)
    var saving by mutableStateOf(false)

    fun load() {
        val existing = ContainerManager(context).getContainerById(appId)
        val variant = existing?.containerVariant ?: PrefManager.containerVariant
        val wine = existing?.wineVersion ?: PrefManager.wineVersion
        windows = existing?.platform != Container.PLATFORM_ANDROID
        canChooseStorage = variant.equals(Container.BIONIC, true) && !WineInfo.isMainWineVersion(wine) &&
            existing?.isOverlay != true && (existing == null || !File(existing.rootDir, ".wine/user.reg").exists())
        if (existing != null) shared = existing.isOverlay || existing.getExtra("overlayRequested") == "1"
        versions = ContentsManager(context).apply { syncContents() }
            .getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK).orEmpty()
            .map { ContentsManager.getEntryName(it).substringAfter('-') }.distinct().sortedDescending()
    }

    fun install(scope: CoroutineScope, onInstall: () -> Unit) {
        if (saving || !ready) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (windows && (canChooseStorage || preset != null || dxvk != null)) {
                        val container = ContainerUtils.getOrCreateContainer(context, appId, installWinePrefix = false)
                        var config = ContainerUtils.toContainerData(container)
                        preset?.let { config = it.apply(config) }
                        dxvk?.let { version ->
                            val options = KeyValueSet(config.dxwrapperConfig).apply { put("version", version) }
                            config = config.copy(dxwrapper = "dxvk", dxwrapperConfig = options.toString())
                        }
                        if (preset != null || dxvk != null) ContainerUtils.applyToContainer(context, container, config, false)
                        if (canChooseStorage) {
                            container.putExtra("overlayRequested", if (shared) "1" else null)
                            container.putExtra("downloadPrefixStorage", if (shared) "shared" else "private")
                        }
                        container.saveData()
                    }
                }
                onInstall()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { SnackbarManager.show(context.getString(R.string.download_container_save_failed)) }
            finally { saving = false }
        }
    }
}

@Composable
internal fun rememberInstallContainerOptions(context: Context, appId: String): InstallContainerOptions {
    val options = remember(appId) { InstallContainerOptions(context, appId) }
    LaunchedEffect(options) {
        try { withContext(Dispatchers.IO) { options.load() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { SnackbarManager.show(context.getString(R.string.container_settings_load_failed)) }
        options.ready = true
    }
    return options
}

@Composable
internal fun InstallContainerSection(options: InstallContainerOptions) {
    if (!options.ready || !options.windows) return
    val enabled = !options.saving
    val automatic = stringResource(R.string.download_container_auto)
    Column {
        SettingsListDropdown(
            title = { Text(stringResource(R.string.download_container_title)) },
            inlineValue = true,
            items = listOf(automatic) + compatPresets.map { stringResource(it.nameRes) },
            value = compatPresets.indexOf(options.preset) + 1,
            enabled = enabled,
            onItemSelected = {
                options.preset = compatPresets.getOrNull(it - 1)
                options.dxvk = null
            },
        )
        if (options.versions.isNotEmpty()) {
            SettingsListDropdown(
                title = { Text("DXVK") },
                inlineValue = true,
                items = listOf(stringResource(R.string.download_container_dx_default)) + options.versions,
                value = options.versions.indexOf(options.dxvk) + 1,
                enabled = enabled,
                onItemSelected = { options.dxvk = options.versions.getOrNull(it - 1) },
            )
        }
        if (options.canChooseStorage) {
            SettingsSwitch(
                state = options.shared, enabled = enabled,
                title = { Text(stringResource(R.string.download_container_shared)) },
                subtitle = { Text(stringResource(R.string.download_container_shared_hint)) },
                onCheckedChange = { options.shared = it },
            )
        }
    }
}
