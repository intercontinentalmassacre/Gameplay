package app.gamenative.ui.gcds

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.gamenative.ui.theme.PluviaTheme

/**
 * Image-backed showcase surface with the shared controller focus contract.
 * Callers own media loading and the content overlay; the frame owns focus,
 * theme-backed shape and a reliable empty-media background.
 */
@Composable
fun GcdsHero(
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    shape: CornerBasedShape = RoundedCornerShape(PluviaTheme.tokens.cornerLg),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable BoxScope.() -> Unit,
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor)
            .then(
                if (interactive) {
                    Modifier
                        .gcdsFocusRing(interactionSource, shape)
                        .selectable(
                            selected = isFocused,
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            ),
        content = content,
    )
}
