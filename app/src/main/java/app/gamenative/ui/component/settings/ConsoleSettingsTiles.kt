package app.gamenative.ui.component.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.focusRing
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults

/**
 * Console focus treatment for settings tiles: quiet at rest, elevated
 * background + focus ring when the controller lands on the tile. Apply to
 * the tile root; the focus state bubbles up from the clickable child.
 */
@Composable
fun Modifier.consoleSettingsTile(): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(0.dp)
    return this
        .padding(vertical = 4.dp)
        .onFocusChanged { isFocused = it.isFocused }
        .then(
            if (isFocused) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            else Modifier,
        )
        .focusRing(isFocused, shape, width = 2.dp)
}

/** Drop-in replacement for the alorma tile with the console focus treatment. */
@Composable
fun SettingsMenuLink(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable () -> Unit,
    subtitle: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    onClick: () -> Unit,
) = com.alorma.compose.settings.ui.SettingsMenuLink(
    // Alorma's tile owns a nested clickable node.  That is fine for touch,
    // but it is not a stable focus target in a secondary Presentation.  Make
    // the row itself focusable and activate it directly with A/Enter.
    modifier = modifier
        .consoleSettingsTile()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionCenter || event.key == Key.Enter)
            ) {
                onClick()
                true
            } else {
                false
            }
        }
        .focusable(enabled = enabled),
    enabled = enabled,
    icon = icon,
    title = title,
    subtitle = subtitle,
    action = action,
    colors = colors,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    onClick = onClick,
)

/** Drop-in replacement for the alorma tile with the console focus treatment. */
@Composable
fun SettingsSwitch(
    state: Boolean,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    onCheckedChange: (Boolean) -> Unit,
) = com.alorma.compose.settings.ui.SettingsSwitch(
    state = state,
    title = title,
    modifier = modifier
        .consoleSettingsTile()
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.DirectionCenter || event.key == Key.Enter)
            ) {
                onCheckedChange(!state)
                true
            } else {
                false
            }
        }
        .focusable(enabled = enabled),
    enabled = enabled,
    icon = icon,
    subtitle = subtitle,
    colors = colors,
    tonalElevation = tonalElevation,
    shadowElevation = shadowElevation,
    onCheckedChange = onCheckedChange,
)
