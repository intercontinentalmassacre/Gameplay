package app.gamenative.ui.gcds

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.ui.component.GamepadHint

/** Forces category strips for companion/dual-screen settings workspaces. */
val LocalForceCategoryStrip = staticCompositionLocalOf { false }

/**
 * One information architecture with two native arrangements:
 * a top category strip when horizontal space is scarce, and a persistent rail
 * when the current display can afford it. The decision is based on the actual
 * content window (including a secondary Presentation), not the phone model.
 */
@Composable
fun <T> GcdsAdaptiveCategoryLayout(
    items: List<T>,
    selectedItem: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    footer: String,
    modifier: Modifier = Modifier,
    footerHints: List<GamepadHint>? = null,
    railWidth: Dp = 228.dp,
    compactBreakpoint: Dp = 600.dp,
    requestInitialFocus: Boolean = true,
    categoriesFocusable: Boolean = true,
    focusRequestKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || items.isEmpty()) {
                return@onPreviewKeyEvent false
            }
            val index = items.indexOf(selectedItem).coerceAtLeast(0)
            when (event.key) {
                Key.ButtonR1, Key.ButtonR2 -> {
                    onSelected(items[(index + 1) % items.size])
                    true
                }
                Key.ButtonL1, Key.ButtonL2 -> {
                    onSelected(items[(index - 1 + items.size) % items.size])
                    true
                }
                else -> false
            }
        },
    ) {
        if (LocalForceCategoryStrip.current || maxWidth < compactBreakpoint) {
            Column(modifier = Modifier.fillMaxSize()) {
                GcdsStrip(
                    items = items,
                    selectedItem = selectedItem,
                    label = label,
                    onSelected = onSelected,
                    requestInitialFocus = requestInitialFocus,
                    controllerFocusable = categoriesFocusable,
                    focusRequestKey = focusRequestKey,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = content)
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                GcdsRail(
                    items = items,
                    selectedItem = selectedItem,
                    label = label,
                    onSelected = onSelected,
                    footer = footer,
                    footerHints = footerHints,
                    requestInitialFocus = requestInitialFocus,
                    controllerFocusable = categoriesFocusable,
                    focusRequestKey = focusRequestKey,
                    compact = true,
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                )
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), content = content)
            }
        }
    }
}
