package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsCPUList
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.container.Container

@Composable
fun AdvancedTabContent(state: ContainerConfigState) {
    val config = state.config.value
    val startupOptions = state.getStartupSelectionOptions()

    SettingsGroup {
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.container_config_startup_services)) },
            value = config.startupSelection.toInt().takeIf { it in startupOptions.indices }
                ?: Container.STARTUP_SELECTION_ESSENTIAL.toInt(),
            items = startupOptions,
            onItemSelected = {
                state.config.value = state.config.value.copy(startupSelection = it.toByte())
            },
        )
    }

    SettingsGroup(title = { Text(stringResource(R.string.processor_affinity)) }) {
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.container_config_affinity_default)) },
            value = config.cpuList,
            onValueChange = {
                state.config.value = state.config.value.copy(cpuList = it)
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(stringResource(R.string.container_config_affinity_32bit)) },
            value = config.cpuListWoW64,
            onValueChange = { state.config.value = state.config.value.copy(cpuListWoW64 = it) },
        )
    }
}
