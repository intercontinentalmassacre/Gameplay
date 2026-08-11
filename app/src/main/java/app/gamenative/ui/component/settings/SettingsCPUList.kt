package app.gamenative.ui.component.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.focusRing
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.base.internal.SettingsTileScaffold

@Composable
fun SettingsCPUList(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    value: String,
    onValueChange: (String) -> Unit,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    action: @Composable (() -> Unit)? = null,
) {
    SettingsTileScaffold(
        modifier = modifier,
        enabled = enabled,
        title = title,
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        action = action,
        subtitle = {
            val cpuAffinity = value.split(",").mapNotNull { it.toIntOrNull() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
                    val isLastSelectedCpu = cpuAffinity.size == 1 && cpuAffinity.contains(cpu)
                    var isFocused by remember { mutableStateOf(false) }
                    val checkboxShape = RoundedCornerShape(6.dp)
                    Column {
                        Checkbox(
                            checked = cpuAffinity.contains(cpu),
                            enabled = enabled && !isLastSelectedCpu,
                            onCheckedChange = {
                                val newAffinity = if (it) {
                                    (cpuAffinity + cpu).sorted()
                                } else {
                                    cpuAffinity.takeIf { it.size > 1 }?.filter { it != cpu } ?: cpuAffinity
                                }
                                onValueChange(newAffinity.joinToString(","))
                            },
                            modifier = Modifier
                                .onFocusChanged { isFocused = it.isFocused }
                                .clip(checkboxShape)
                                .background(
                                    if (isFocused) {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .focusRing(isFocused, checkboxShape, width = 2.dp),
                        )
                        Text(stringResource(R.string.cpu_label, cpu))
                    }
                }
            }
        },
    )
}
