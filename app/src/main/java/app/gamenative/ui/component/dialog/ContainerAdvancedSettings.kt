package app.gamenative.ui.component.dialog

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsMenuLink
import app.gamenative.ui.theme.settingsTileColorsAlt

@Composable
internal fun ContainerAdvancedSettings(
    disclose: Boolean = true,
    @StringRes titleRes: Int = R.string.container_config_tab_advanced,
    content: @Composable () -> Unit,
) {
    if (!disclose) {
        content()
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsMenuLink(
        title = { Text(stringResource(titleRes)) },
        action = {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier = Modifier.semantics {
            if (expanded) {
                collapse {
                    expanded = false
                    true
                }
            } else {
                expand {
                    expanded = true
                    true
                }
            }
        },
        colors = settingsTileColorsAlt(),
        onClick = { expanded = !expanded },
    )
    if (expanded) content()
}
