package app.gamenative.ui.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Drop-in replacement for the material3 Slider with a focus ring around the
 * track so controller focus is visible (the default thumb halo is too subtle
 * against the app's dark surfaces).
 */
@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()

    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRing(
            resolvedInteractionSource,
            RoundedCornerShape(10.dp),
            width = 2.dp,
        ),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = resolvedInteractionSource,
    )
}
