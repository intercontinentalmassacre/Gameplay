package app.gamenative.externaldisplay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.component.QuickMenuAction

@Composable
internal fun BuranSessionQuickMenu(
    channel: BuranCompanionControls,
    onDismiss: () -> Unit,
    onKeyboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    upperScreen: Boolean = false,
) {
    val actions = remember(channel, channel.hudOnLowerScreen, onKeyboard != null) {
        buildSet {
            if (onKeyboard != null && "keyboard" in channel.capabilities) add(QuickMenuAction.KEYBOARD)
            if ("disableMouse" in channel.capabilities) add(QuickMenuAction.DISABLE_MOUSE)
            if ("touchscreenMode" in channel.capabilities) add(QuickMenuAction.TOUCHSCREEN_MODE)
            if (channel.supportsPerformanceHud || (!channel.hudOnLowerScreen && "outputFps" in channel.capabilities)) {
                add(QuickMenuAction.PERFORMANCE_HUD)
            }
            if ("restart" in channel.capabilities) add(QuickMenuAction.CONTAINER_RESTART)
        }
    }
    QuickMenu(
        isVisible = true,
        onDismiss = onDismiss,
        onItemSelected = { action ->
            when (action) {
                QuickMenuAction.EXIT_GAME -> {
                    channel.stop()
                    onDismiss()
                }
                QuickMenuAction.KEYBOARD -> onKeyboard?.invoke()
                QuickMenuAction.DISABLE_MOUSE -> channel.toggleMouse()
                QuickMenuAction.TOUCHSCREEN_MODE -> channel.toggleTouchscreen()
                QuickMenuAction.PERFORMANCE_HUD -> channel.togglePerformanceHud()
                QuickMenuAction.MANGO_HUD -> channel.toggleMangoHud()
                QuickMenuAction.CONTAINER_RESTART -> {
                    channel.restart()
                    onDismiss()
                }
            }
            false
        },
        fullScreen = true,
        modifier = modifier,
        externalOverlay = upperScreen,
        availableControllerActions = actions,
        exitEnabled = !channel.stopping,
        activeToggleIds = buildSet {
            if (channel.mouseDisabled) add(QuickMenuAction.DISABLE_MOUSE)
            if (channel.touchscreen) add(QuickMenuAction.TOUCHSCREEN_MODE)
        },
        isPerformanceHudEnabled = channel.performanceHudEnabled,
        performanceHudConfig = channel.performanceHudConfig,
        onPerformanceHudConfigChanged = channel::changePerformanceHudConfig,
        externalPerformanceHudAvailable = channel.supportsPerformanceHud,
        externalMangoHudEnabled = channel.mangoHudEnabled.takeIf { channel.mangoHudAvailable && !channel.hudOnLowerScreen },
        externalFramegenEnabled = channel.framegenActive.takeIf { channel.supportsFramegen },
        externalFramegenPending = channel.framegenPending,
        externalFramegenFailed = channel.framegenFailed,
        onExternalFramegenToggle = channel::toggleFramegen,
        externalFpsLimiterAvailable = "fpsLimit" in channel.capabilities,
        externalFpsLimiterMin = 15,
        fpsLimiterEnabled = channel.fpsLimiterEnabled,
        fpsLimiterTarget = channel.fpsTarget,
        fpsLimiterMax = 120,
        onFpsLimiterEnabledChanged = channel::setFpsLimiter,
        onFpsLimiterChanged = channel::changeFpsTarget,
    )
}
