package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.DropdownMenu
import app.gamenative.ui.component.DropdownMenuItem
import androidx.compose.material3.Icon
import app.gamenative.ui.component.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.component.settings.SettingsEnvVars
import app.gamenative.ui.component.settings.SettingsMultiListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import app.gamenative.ui.component.settings.SettingsMenuLink
import com.winlator.core.envvars.EnvVarInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.core.envvars.EnvVarSelectionType

@Composable
fun EnvironmentTabContent(state: ContainerConfigState) {
    val config = state.config.value
    val envVars = EnvVars(config.envVars)
    SettingsGroup {
        SettingsMenuLink(
            colors = settingsTileColors(),
            icon = { Icon(Icons.Outlined.AddCircleOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.container_env_add_variable)) },
            onClick = { state.showEnvVarCreateDialog.value = true },
        )
        if (!envVars.isEmpty()) {
            SettingsEnvVars(
                colors = settingsTileColors(),
                envVars = envVars,
                onEnvVarsChange = {
                    state.config.value = state.config.value.copy(envVars = it.toString())
                },
                knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS,
                envVarAction = { identifier ->
                    IconButton(
                        onClick = {
                            val draft = state.config.value
                            val remaining = EnvVars(draft.envVars).apply { remove(identifier) }
                            state.config.value = draft.copy(envVars = remaining.toString())
                        },
                        content = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.container_env_delete_variable, identifier),
                            )
                        },
                    )
                },
            )
        } else {
            SettingsCenteredLabel(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.no_environment_variables)) },
            )
        }
    }

    if (state.showEnvVarCreateDialog.value) {
        var envVarName by rememberSaveable { mutableStateOf("") }
        var envVarValue by rememberSaveable { mutableStateOf("") }
        val variableName = envVarName.trim()
        val nameError = when {
            '=' in variableName || '\u0000' in variableName -> R.string.container_env_invalid_name
            envVars.has(variableName) -> R.string.container_env_duplicate_name
            else -> null
        }
        AlertDialog(
            onDismissRequest = { state.showEnvVarCreateDialog.value = false },
            title = { Text(text = stringResource(R.string.new_environment_variable)) },
            text = {
                var knownVarsMenuOpen by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        NoExtractOutlinedTextField(
                            value = envVarName,
                            onValueChange = { envVarName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.name)) },
                            singleLine = true,
                            isError = nameError != null,
                            supportingText = if (nameError != null) {
                                { Text(stringResource(nameError)) }
                            } else null,
                            trailingIcon = {
                                IconButton(
                                    onClick = { knownVarsMenuOpen = true },
                                    content = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = stringResource(R.string.container_env_known_variables),
                                        )
                                    },
                                )
                            },
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = knownVarsMenuOpen,
                            onDismissRequest = { knownVarsMenuOpen = false },
                        ) {
                            val knownEnvVars = EnvVarInfo.KNOWN_ENV_VARS.values.filterNot { envVars.has(it.identifier) }
                            if (knownEnvVars.isNotEmpty()) {
                                for (knownVariable in knownEnvVars) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(knownVariable.identifier) },
                                        onClick = {
                                            envVarName = knownVariable.identifier
                                            knownVarsMenuOpen = false
                                        },
                                    )
                                }
                            } else {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(text = stringResource(R.string.no_more_known_variables)) },
                                    onClick = {},
                                )
                            }
                        }
                    }
                    val selectedEnvVarInfo = EnvVarInfo.KNOWN_ENV_VARS[variableName]
                    if (selectedEnvVarInfo?.selectionType == EnvVarSelectionType.MULTI_SELECT) {
                        val multiSelectedIndices = envVarValue.split(',')
                            .map { selectedEnvVarInfo.possibleValues.indexOf(it) }
                            .filter { it >= 0 }
                        SettingsMultiListDropdown(
                            enabled = true,
                            values = multiSelectedIndices,
                            items = selectedEnvVarInfo.possibleValues,
                            fallbackDisplay = "",
                            onItemSelected = { index ->
                                val newIndices = if (multiSelectedIndices.contains(index)) {
                                    multiSelectedIndices.filter { it != index }
                                } else {
                                    multiSelectedIndices + index
                                }
                                envVarValue = newIndices.joinToString(",") { selectedEnvVarInfo.possibleValues[it] }
                            },
                            title = { Text(text = stringResource(R.string.value)) },
                            colors = settingsTileColors(),
                        )
                    } else {
                        var suggestionsExpanded by remember { mutableStateOf(false) }
                        val hasSuggestions = selectedEnvVarInfo?.selectionType == EnvVarSelectionType.SUGGESTIONS
                        NoExtractOutlinedTextField(
                            value = envVarValue,
                            onValueChange = { envVarValue = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(text = stringResource(R.string.value)) },
                            singleLine = true,
                            trailingIcon = if (hasSuggestions) {
                                {
                                    IconButton(onClick = { suggestionsExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                            contentDescription = stringResource(R.string.container_env_value_presets),
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = suggestionsExpanded,
                                        onDismissRequest = { suggestionsExpanded = false },
                                    ) {
                                        selectedEnvVarInfo!!.possibleValues.forEach { suggestion ->
                                            // suggestion box headers
                                            if (suggestion.startsWith("---")) {
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = suggestion.removePrefix("---"),
                                                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                        )
                                                    },
                                                    onClick = {},
                                                    enabled = false,
                                                )
                                            } else {
                                                DropdownMenuItem(
                                                    text = { Text(suggestion) },
                                                    onClick = {
                                                        envVarValue = suggestion
                                                        suggestionsExpanded = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            } else null,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { state.showEnvVarCreateDialog.value = false },
                    content = { Text(text = stringResource(R.string.cancel)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = variableName.isNotEmpty() && nameError == null,
                    onClick = {
                        val draft = state.config.value
                        val updated = EnvVars(draft.envVars).apply { put(variableName, envVarValue) }
                        state.config.value = draft.copy(envVars = updated.toString())
                        state.showEnvVarCreateDialog.value = false
                    },
                    content = { Text(stringResource(R.string.add)) },
                )
            },
        )
    }
}
