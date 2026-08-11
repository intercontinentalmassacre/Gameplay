package app.gamenative.ui.gcds

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme

/** Controller hint bar (A/B/X/Y glyph actions) — GCDS alias of GamepadActionBar. */
@Composable
fun GcdsActionBar(
    actions: List<GamepadAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    forceVisible: Boolean = false,
    compact: Boolean = false,
) = GamepadActionBar(
    actions = actions,
    modifier = modifier,
    visible = visible,
    forceVisible = forceVisible,
    compact = compact,
)

/** Token-driven focus ring: width defaults to the GCDS focus token. */
@Composable
fun Modifier.gcdsFocusRing(
    interactionSource: MutableInteractionSource,
    shape: CornerBasedShape,
    width: Dp? = null,
): Modifier = this.focusRing(
    interactionSource = interactionSource,
    shape = shape,
    width = width ?: PluviaTheme.tokens.focusRingWidth,
)
