package app.gamenative.ui.gcds

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import app.gamenative.ui.screen.library.components.ConsoleSidePanel
import app.gamenative.ui.theme.PluviaTheme

/**
 * Global system-layer surface.
 *
 * The wrapper owns the 60% system scrim, modal elevation and first-item focus
 * contract; callers supply only the menu content. Quick menus deliberately use
 * their own 40% scrim and are therefore not routed through this component.
 */
@Composable
fun GcdsSystemMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequestKey: Any? = null,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    ConsoleSidePanel(
        isOpen = isVisible,
        onDismiss = onDismiss,
        modifier = modifier,
        focusRequestKey = focusRequestKey,
        scrimAlpha = 0.6f,
        surfaceElevation = PluviaTheme.tokens.elevationModal,
        content = content,
    )
}
