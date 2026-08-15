package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.rememberControllerConnectionState
import kotlinx.coroutines.delay

/** Shared controller-first category navigation for complex settings surfaces. */
@Composable
fun <T> ConsoleCategoryRail(
    items: List<T>,
    selectedItem: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    footer: String,
    modifier: Modifier = Modifier,
    footerHints: List<GamepadHint>? = null,
    requestInitialFocus: Boolean = false,
    controllerFocusable: Boolean = true,
    focusRequestKey: Any? = null,
    compact: Boolean = false,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val controllerConnection = rememberControllerConnectionState()
    LaunchedEffect(requestInitialFocus, items, controllerConnection.generation, focusRequestKey) {
        if (requestInitialFocus && items.isNotEmpty()) {
            // Presentation content is attached a few frames after its window
            // receives controller focus. requestFocus() can return false here
            // without throwing; retry until the first category is a real
            // controller target instead of silently leaving LB/RB as the only
            // working controls.
            repeat(8) {
                if (runCatching { initialFocusRequester.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
                delay(32)
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 10.dp else 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
    ) {
        items.forEach { item ->
            val interactionSource = remember(item) { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val selected = item == selectedItem
            val shape = RoundedCornerShape(10.dp)
            Text(
                text = label(item),
                modifier = Modifier
                    .then(if (requestInitialFocus && item == items.firstOrNull()) Modifier.focusRequester(initialFocusRequester) else Modifier)
                    .focusProperties { canFocus = controllerFocusable }
                    .fillMaxWidth()
                    .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
                    .clip(shape)
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primaryContainer
                            focused -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> Color.Transparent
                        },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelected(item) },
                    )
                    .padding(
                        horizontal = if (compact) 12.dp else 16.dp,
                        vertical = if (compact) 10.dp else 13.dp,
                    ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    focused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (footerHints != null) {
            GamepadHintRow(
                hints = footerHints,
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = if (compact) 2.dp else 6.dp,
                ),
            )
        } else {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = if (compact) 2.dp else 6.dp,
                ),
            )
        }
    }
}

/**
 * Horizontal variant of [ConsoleCategoryRail] for narrow surfaces (dual-screen lower
 * display, phones in portrait): the same chip vocabulary and selection colors, laid out
 * as a scrollable strip instead of a vertical rail.
 */
@Composable
fun <T> ConsoleCategoryStrip(
    items: List<T>,
    selectedItem: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    controllerFocusable: Boolean = true,
    focusRequestKey: Any? = null,
) {
    val initialFocusRequester = remember { FocusRequester() }
    val controllerConnection = rememberControllerConnectionState()
    LaunchedEffect(requestInitialFocus, items, controllerConnection.generation, focusRequestKey) {
        if (requestInitialFocus && items.isNotEmpty()) {
            repeat(8) {
                if (runCatching { initialFocusRequester.requestFocus() }.getOrDefault(false)) {
                    return@LaunchedEffect
                }
                delay(32)
            }
        }
    }
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val interactionSource = remember(item) { MutableInteractionSource() }
            val focused by interactionSource.collectIsFocusedAsState()
            val selected = item == selectedItem
            val shape = RoundedCornerShape(10.dp)
            Text(
                text = label(item),
                maxLines = 1,
                modifier = Modifier
                    .then(if (requestInitialFocus && item == items.firstOrNull()) Modifier.focusRequester(initialFocusRequester) else Modifier)
                    .focusProperties { canFocus = controllerFocusable }
                    .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
                    .clip(shape)
                    .background(
                        when {
                            selected -> MaterialTheme.colorScheme.primaryContainer
                            focused -> MaterialTheme.colorScheme.surfaceContainerHighest
                            else -> Color.Transparent
                        },
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSelected(item) },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    focused -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
