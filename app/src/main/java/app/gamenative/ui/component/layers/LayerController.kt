package app.gamenative.ui.component.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Two-axis navigation layer model. Higher priority wins for focus and Back.
 *
 * - [L0] game — running game viewport
 * - [L1] shell — library / details / downloads / settings screens
 * - [L2] quick — the quick menu overlay (single instance)
 * - [L3] system — the global system menu overlay (single instance)
 * - [L4] dialog — modal dialogs (stacked, top-first on Back)
 */
enum class Layer(val priority: Int) {
    L0_Game(0),
    L1_Shell(1),
    L2_Quick(2),
    L3_System(3),
    L4_Dialog(4),
}

/**
 * Tracks the active navigation layer and the L2/L3 overlay toggles.
 * Exactly one of [isQuickOpen] / [isSystemOpen] can be true at a time;
 * opening one closes the other. [onBack] closes the top-most open layer first.
 */
@Stable
class LayerController {
    var baseLayer by mutableStateOf(Layer.L1_Shell)
        private set

    var isQuickOpen by mutableStateOf(false)
        private set

    var isSystemOpen by mutableStateOf(false)
        private set

    private var dialogCount by mutableIntStateOf(0)

    /** Highest-priority layer currently visible. */
    val activeLayer: Layer
        get() = when {
            dialogCount > 0 -> Layer.L4_Dialog
            isSystemOpen -> Layer.L3_System
            isQuickOpen -> Layer.L2_Quick
            else -> baseLayer
        }

    /** True when any overlay (L2+) is above the base layer. */
    val hasOverlay: Boolean
        get() = activeLayer.priority > baseLayer.priority

    fun enterGame() {
        baseLayer = Layer.L0_Game
    }

    fun enterShell() {
        baseLayer = Layer.L1_Shell
    }

    fun openQuick() {
        isSystemOpen = false
        isQuickOpen = true
    }

    fun closeQuick() {
        isQuickOpen = false
    }

    fun openSystem() {
        isQuickOpen = false
        isSystemOpen = true
    }

    fun closeSystem() {
        isSystemOpen = false
    }

    fun pushDialog() {
        dialogCount++
    }

    fun popDialog() {
        if (dialogCount > 0) dialogCount--
    }

    fun clearDialogs() {
        dialogCount = 0
    }

    /** Back handling: closes the top-most open layer. Returns true if consumed. */
    fun onBack(): Boolean {
        return when {
            dialogCount > 0 -> {
                dialogCount--
                true
            }
            isSystemOpen -> {
                isSystemOpen = false
                true
            }
            isQuickOpen -> {
                isQuickOpen = false
                true
            }
            else -> false
        }
    }
}

/**
 * CompositionLocal exposing the active [LayerController] to the shell and game
 * screens. Provided once at the top of [app.gamenative.ui.PluviaMain] so any
 * screen can open/close the global system overlay (L3) without prop-drilling.
 * Nullable so previews and tests without a provider can read it safely.
 */
val LocalLayerController = staticCompositionLocalOf<LayerController?> { null }

@Composable
fun rememberLayerController(): LayerController = remember { LayerController() }

@Composable
fun ProvideLayerController(controller: LayerController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayerController provides controller) { content() }
}
