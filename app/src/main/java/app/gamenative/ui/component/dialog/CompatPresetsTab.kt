package app.gamenative.ui.component.dialog

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.Button
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.ui.component.settings.SettingsMenuLink
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet

/**
 * Built-in compatibility presets: proven combinations of container settings
 * with plain-language explanations. Applying a preset only edits the in-memory
 * config; the user still saves explicitly and can fine-tune afterwards.
 */
private data class CompatPreset(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    @StringRes val changesRes: Int,
    val apply: (ContainerData) -> ContainerData,
)

private fun mergedDxwrapperConfig(config: ContainerData, vararg values: Pair<String, Any>): String {
    val kvs = KeyValueSet(config.dxwrapperConfig)
    values.forEach { (key, value) -> kvs.put(key, value) }
    return kvs.toString()
}

private fun mergedDriverConfig(config: ContainerData, vararg values: Pair<String, Any>): String {
    val kvs = KeyValueSet(config.graphicsDriverConfig)
    values.forEach { (key, value) -> kvs.put(key, value) }
    return kvs.toString()
}

private val compatPresets = listOf(
    CompatPreset(
        id = "PERFORMANCE",
        nameRes = R.string.compat_preset_performance_name,
        descRes = R.string.compat_preset_performance_desc,
        changesRes = R.string.compat_preset_performance_changes,
        apply = { config ->
            val isBionic = config.containerVariant.equals(Container.BIONIC, ignoreCase = true)
            config.copy(
                dxwrapper = "dxvk",
                dxwrapperConfig = mergedDxwrapperConfig(
                    config,
                    "version" to "3.0-1-gplasync-0",
                    "async" to "1",
                    "asyncCache" to "1",
                ),
                box64Preset = Box86_64Preset.PERFORMANCE,
                box86Preset = Box86_64Preset.PERFORMANCE,
                fexcoreVersion = "2601-217d039-0",
                graphicsDriver = if (isBionic) "wrapper" else config.graphicsDriver,
                graphicsDriverConfig = mergedDriverConfig(
                    config,
                    *if (isBionic) arrayOf("version" to "Mesa Turnip v26.3.0-20260808-r9") else emptyArray(),
                    "maxDeviceMemory" to "4096",
                    "presentMode" to "mailbox",
                    "bcnEmulation" to "auto",
                ),
            )
        },
    ),
    CompatPreset(
        id = "STABILITY",
        nameRes = R.string.compat_preset_stability_name,
        descRes = R.string.compat_preset_stability_desc,
        changesRes = R.string.compat_preset_stability_changes,
        apply = { config ->
            val isBionic = config.containerVariant.equals(Container.BIONIC, ignoreCase = true)
            config.copy(
                dxwrapper = "dxvk",
                dxwrapperConfig = mergedDxwrapperConfig(
                    config,
                    "version" to "2.7.1",
                    "async" to "0",
                    "asyncCache" to "0",
                ),
                box64Preset = Box86_64Preset.COMPATIBILITY,
                box86Preset = Box86_64Preset.COMPATIBILITY,
                strictShaderMath = true,
                fexcoreVersion = "2601-217d039-0",
                graphicsDriver = if (isBionic) "wrapper" else config.graphicsDriver,
                graphicsDriverConfig = mergedDriverConfig(
                    config,
                    *if (isBionic) arrayOf("version" to "Stable Turnip v1") else emptyArray(),
                    "presentMode" to "fifo",
                ),
            )
        },
    ),
    CompatPreset(
        id = "OLD_GAMES",
        nameRes = R.string.compat_preset_old_games_name,
        descRes = R.string.compat_preset_old_games_desc,
        changesRes = R.string.compat_preset_old_games_changes,
        apply = { config ->
            config.copy(
                dxwrapper = "cnc-ddraw",
                screenSize = "1280x960",
                videoMemorySize = "256",
                csmt = true,
            )
        },
    ),
    CompatPreset(
        id = "DX12",
        nameRes = R.string.compat_preset_dx12_name,
        descRes = R.string.compat_preset_dx12_desc,
        changesRes = R.string.compat_preset_dx12_changes,
        apply = { config ->
            config.copy(
                dxwrapper = "vkd3d",
                dxwrapperConfig = mergedDxwrapperConfig(
                    config,
                    "vkd3dVersion" to "3.0.1-0",
                    "vkd3dFeatureLevel" to "12_1",
                ),
            )
        },
    ),
    CompatPreset(
        id = "LOW_MEMORY",
        nameRes = R.string.compat_preset_low_memory_name,
        descRes = R.string.compat_preset_low_memory_desc,
        changesRes = R.string.compat_preset_low_memory_changes,
        apply = { config ->
            config.copy(
                videoMemorySize = "1024",
                graphicsDriverConfig = mergedDriverConfig(
                    config,
                    "maxDeviceMemory" to "1024",
                ),
                startupSelection = Container.STARTUP_SELECTION_AGGRESSIVE,
                steamType = Container.STEAM_TYPE_LIGHT,
            )
        },
    ),
)

@Composable
fun CompatPresetsTabContent(state: ContainerConfigState) {
    var lastAppliedId by remember { mutableStateOf<String?>(null) }
    var selectedPresetId by remember { mutableStateOf<String?>(null) }
    val selectedPreset = compatPresets.firstOrNull { it.id == selectedPresetId }

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = stringResource(R.string.compat_presets_choose_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.compat_presets_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        compatPresets.forEachIndexed { index, preset ->
            val isSelected = selectedPresetId == preset.id
            SettingsMenuLink(
                colors = if (index % 2 == 0) settingsTileColors() else settingsTileColorsAlt(),
                title = {
                    Text(
                        text = stringResource(preset.nameRes),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                subtitle = {
                    Column {
                        Text(stringResource(preset.descRes))
                        Text(
                            text = stringResource(preset.changesRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                },
                action = if (isSelected || lastAppliedId == preset.id) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(
                                if (lastAppliedId == preset.id) R.string.compat_preset_applied
                                else R.string.compat_presets_selected,
                            ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    selectedPresetId = preset.id
                },
            )
        }

        selectedPreset?.let { preset ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.compat_presets_ready_title, stringResource(preset.nameRes)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.compat_presets_apply_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Button(
                        onClick = {
                            state.config.value = preset.apply(state.config.value)
                            lastAppliedId = preset.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.compat_presets_apply))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
