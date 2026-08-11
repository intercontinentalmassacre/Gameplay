package app.gamenative.ui.gcds

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.gamenative.ui.component.ConsoleCategoryRail
import app.gamenative.ui.component.ConsoleCategoryStrip
import app.gamenative.ui.component.GamepadHint

/**
 * GCDS navigation contracts. Thin wrappers over the console category primitives so
 * call sites depend on the design-system API, not on implementation details.
 */

@Composable
fun <T> GcdsRail(
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
) = ConsoleCategoryRail(
    items = items,
    selectedItem = selectedItem,
    label = label,
    onSelected = onSelected,
    footer = footer,
    modifier = modifier,
    footerHints = footerHints,
    requestInitialFocus = requestInitialFocus,
    controllerFocusable = controllerFocusable,
    focusRequestKey = focusRequestKey,
    compact = compact,
)

@Composable
fun <T> GcdsStrip(
    items: List<T>,
    selectedItem: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    requestInitialFocus: Boolean = false,
    controllerFocusable: Boolean = true,
    focusRequestKey: Any? = null,
) = ConsoleCategoryStrip(
    items = items,
    selectedItem = selectedItem,
    label = label,
    onSelected = onSelected,
    modifier = modifier,
    requestInitialFocus = requestInitialFocus,
    controllerFocusable = controllerFocusable,
    focusRequestKey = focusRequestKey,
)
