package app.gamenative.ui.gcds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCollection
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.data.DownloadsState
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.screen.library.components.ConsolePanelBackHint
import app.gamenative.ui.screen.library.components.ConsolePanelHeader
import app.gamenative.ui.screen.library.components.ConsoleSidePanel
import app.gamenative.ui.screen.library.components.LibraryOptionsPanel
import app.gamenative.ui.screen.xserver.InGameContainerSettings
import app.gamenative.utils.rememberHasExternalDisplay
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo
import com.winlator.widget.FrameRating
import java.util.EnumSet

/**
 * Context a quick menu is opened for. Content is role-specific: in-game it
 * drives the running game, in the library it exposes filters/sort/views and in
 * downloads it controls the queue.
 */
enum class GcdsQuickMenuRole {
    GAME,
    LIBRARY,
    DOWNLOADS,
}

/**
 * Unified quick menu for GCDS. Replaces the previous two-component split
 * ([QuickMenu] + the dual-display wrapper) with one role-driven entry point.
 *
 * The [GcdsQuickMenuRole.GAME] role keeps the dual-display-aware behaviour the
 * old wrapper had: when an external (second) display is attached, the menu
 * stays visible there while the game keeps input focus; Back activates the
 * second-display menu and Back again returns focus to the game. Without a
 * second display this renders as the usual full-screen overlay.
 *
 * The close/resume callback is fired here because on the second display the
 * menu's composition is torn down with the presentation instead of running its
 * exit animation.
 */
@Composable
fun GcdsQuickMenu(
    role: GcdsQuickMenuRole,
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    // GAME role state
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    fpsLimiterEnabled: Boolean = true,
    fpsLimiterTarget: Int = 60,
    fpsLimiterMax: Int = 60,
    game: SteamApp? = null,
    frameRating: FrameRating? = null,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    onFpsLimiterChanged: (Int) -> Unit = {},
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    isShooterModeActive: Boolean = false,
    onShooterModeSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    // LSFG hot-reload state (tab only visible when isLsfgAvailable)
    isLsfgAvailable: Boolean = false,
    lsfgMultiplier: Int = 2,
    lsfgFlowScale: Float = 0.80f,
    lsfgPerformanceMode: Boolean = true,
    onLsfgMultiplierChanged: (Int) -> Unit = {},
    onLsfgFlowScaleChanged: (Float) -> Unit = {},
    onLsfgPerformanceModeChanged: (Boolean) -> Unit = {},
    onAnimationComplete: (Boolean) -> Unit = {},
    /** Lets the menu open itself when the running game asks for its Steam invite dialog. */
    onRequestOpen: () -> Unit = {},
    // LIBRARY role state (filters/sort/views)
    selectedFilters: EnumSet<AppFilter> = EnumSet.noneOf(AppFilter::class.java),
    onFilterChanged: (AppFilter) -> Unit = {},
    currentSortOption: SortOption = SortOption.INSTALLED_FIRST,
    onSortOptionChanged: (SortOption) -> Unit = {},
    steamCollections: List<SteamCollection>? = null,
    selectedSteamCollectionIds: Set<String> = emptySet(),
    steamCollectionCounts: Map<String, Int> = emptyMap(),
    skippedDynamicCollections: Boolean = false,
    isSteamConnected: Boolean = false,
    isOffline: Boolean = false,
    onSteamCollectionToggle: (String) -> Unit = {},
    onClearSteamCollections: () -> Unit = {},
    // DOWNLOADS role state (queue controls)
    downloadsState: DownloadsState = DownloadsState(),
    onDownloadsPauseAll: () -> Unit = {},
    onDownloadsResumeAll: () -> Unit = {},
    onDownloadsCancelAll: () -> Unit = {},
    onDownloadsClearFinished: () -> Unit = {},
) {
    when (role) {
        GcdsQuickMenuRole.GAME -> GcdsGameQuickMenu(
            isVisible = isVisible,
            onDismiss = onDismiss,
            onItemSelected = onItemSelected,
            renderer = renderer,
            glRenderer = glRenderer,
            container = container,
            wineProcesses = wineProcesses,
            isWineProcessesLoading = isWineProcessesLoading,
            onToolsVisibilityChanged = onToolsVisibilityChanged,
            onEndWineProcess = onEndWineProcess,
            isPerformanceHudEnabled = isPerformanceHudEnabled,
            performanceHudConfig = performanceHudConfig,
            fpsLimiterEnabled = fpsLimiterEnabled,
            fpsLimiterTarget = fpsLimiterTarget,
            fpsLimiterMax = fpsLimiterMax,
            game = game,
            frameRating = frameRating,
            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
            onFpsLimiterChanged = onFpsLimiterChanged,
            hasPhysicalController = hasPhysicalController,
            isTouchscreenModeActive = isTouchscreenModeActive,
            onTouchGestureSettingsClick = onTouchGestureSettingsClick,
            isShooterModeActive = isShooterModeActive,
            onShooterModeSettingsClick = onShooterModeSettingsClick,
            activeToggleIds = activeToggleIds,
            isLsfgAvailable = isLsfgAvailable,
            lsfgMultiplier = lsfgMultiplier,
            lsfgFlowScale = lsfgFlowScale,
            lsfgPerformanceMode = lsfgPerformanceMode,
            onLsfgMultiplierChanged = onLsfgMultiplierChanged,
            onLsfgFlowScaleChanged = onLsfgFlowScaleChanged,
            onLsfgPerformanceModeChanged = onLsfgPerformanceModeChanged,
            onAnimationComplete = onAnimationComplete,
            onRequestOpen = onRequestOpen,
            modifier = modifier,
        )

        GcdsQuickMenuRole.LIBRARY -> GcdsLibraryQuickMenu(
            isVisible = isVisible,
            onDismiss = onDismiss,
            selectedFilters = selectedFilters,
            onFilterChanged = onFilterChanged,
            currentSortOption = currentSortOption,
            onSortOptionChanged = onSortOptionChanged,
            steamCollections = steamCollections,
            selectedSteamCollectionIds = selectedSteamCollectionIds,
            steamCollectionCounts = steamCollectionCounts,
            skippedDynamicCollections = skippedDynamicCollections,
            isSteamConnected = isSteamConnected,
            isOffline = isOffline,
            onSteamCollectionToggle = onSteamCollectionToggle,
            onClearSteamCollections = onClearSteamCollections,
            modifier = modifier,
        )

        GcdsQuickMenuRole.DOWNLOADS -> GcdsDownloadsQuickMenu(
            isVisible = isVisible,
            onDismiss = onDismiss,
            state = downloadsState,
            onPauseAll = onDownloadsPauseAll,
            onResumeAll = onDownloadsResumeAll,
            onCancelAll = onDownloadsCancelAll,
            onClearFinished = onDownloadsClearFinished,
            modifier = modifier,
        )
    }
}

/**
 * GAME role: dual-display-aware quick menu driving the running game.
 * Keeps the passive second-display composition alive so invite polling can
 * open the menu; if Presentation.show() fails, this same instance remains the
 * fully usable visible fallback.
 */
@Composable
private fun GcdsGameQuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    isPerformanceHudEnabled: Boolean = false,
    performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    fpsLimiterEnabled: Boolean = true,
    fpsLimiterTarget: Int = 60,
    fpsLimiterMax: Int = 60,
    game: SteamApp? = null,
    frameRating: FrameRating? = null,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    onFpsLimiterChanged: (Int) -> Unit = {},
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    isShooterModeActive: Boolean = false,
    onShooterModeSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    isLsfgAvailable: Boolean = false,
    lsfgMultiplier: Int = 2,
    lsfgFlowScale: Float = 0.80f,
    lsfgPerformanceMode: Boolean = true,
    onLsfgMultiplierChanged: (Int) -> Unit = {},
    onLsfgFlowScaleChanged: (Float) -> Unit = {},
    onLsfgPerformanceModeChanged: (Boolean) -> Unit = {},
    onAnimationComplete: (Boolean) -> Unit = {},
    onRequestOpen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val hasExternalDisplay = rememberHasExternalDisplay()
    val dashboardSubtitle = stringResource(R.string.second_screen_fps_target, fpsLimiterTarget)
    val dashboardTitle = game?.name.orEmpty().ifBlank { container?.name.orEmpty() }
    val dashboardImageUrl = game?.getCapsuleUrl(large = true)
        ?.takeIf { it.isNotBlank() }
        ?: game?.headerUrl.orEmpty()
    val dashboardLogoUrl = game?.let { app ->
        app.getLogoUrl(large = true)
            ?: app.getLogoUrl()
            ?: app.logoUrl.takeIf { app.logoHash.isNotBlank() }
    }.orEmpty().takeIf { PrefManager.dualScreenGameUseLogo }.orEmpty()
    val lowerPanel = remember(container?.id) {
        mutableStateOf(PrefManager.dualScreenGameDefaultPanel)
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            lowerPanel.value = 1
        } else if (!InGameContainerSettings.overlayActive) {
            lowerPanel.value = PrefManager.dualScreenGameDefaultPanel
        }
    }

    if (hasExternalDisplay) {
        SideEffect {
            DsHomeSecondScreen.publish(DsHomeSecondScreen.Model(
                owner = DsHomeSecondScreen.Owner.GAME,
                mode = if (lowerPanel.value == 1) {
                    DsHomeSecondScreen.Mode.QUICK_MENU
                } else {
                    DsHomeSecondScreen.Mode.GAME_DASHBOARD
                },
                dashboardTitle = dashboardTitle,
                dashboardSubtitle = dashboardSubtitle,
                dashboardImageUrl = dashboardImageUrl,
                dashboardLogoUrl = dashboardLogoUrl,
                performanceHudEnabled = isPerformanceHudEnabled,
                performanceHudConfig = performanceHudConfig,
                performanceHudFpsProvider = {
                    val raw = frameRating?.currentFPS ?: 0f
                    if (raw.isFinite()) raw.coerceAtLeast(0f) else 0f
                },
                performanceHudKey = 31 * System.identityHashCode(frameRating),
                onShowDashboard = {
                    lowerPanel.value = 0
                    if (isVisible) onDismiss()
                },
                onShowMenu = {
                    lowerPanel.value = 1
                    // Force an outer state change as well: the Presentation owns
                    // a separate Compose frame clock, so changing only the state
                    // captured by this callback is not enough to reliably wake
                    // the main-display publisher on every device. The lower
                    // window remains NOT_FOCUSABLE, so controller focus still
                    // stays with the game.
                    if (!isVisible) onRequestOpen()
                },
                menuContent = {
                    QuickMenu(
                        isVisible = true,
                        onDismiss = onDismiss,
                        onItemSelected = onItemSelected,
                        renderer = renderer,
                        glRenderer = glRenderer,
                        container = container,
                        wineProcesses = wineProcesses,
                        isWineProcessesLoading = isWineProcessesLoading,
                        onToolsVisibilityChanged = { toolsVisible ->
                            onToolsVisibilityChanged(isVisible && toolsVisible)
                        },
                        onEndWineProcess = onEndWineProcess,
                        isPerformanceHudEnabled = isPerformanceHudEnabled,
                        performanceHudConfig = performanceHudConfig,
                        fpsLimiterEnabled = fpsLimiterEnabled,
                        fpsLimiterTarget = fpsLimiterTarget,
                        fpsLimiterMax = fpsLimiterMax,
                        onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                        onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
                        onFpsLimiterChanged = onFpsLimiterChanged,
                        hasPhysicalController = hasPhysicalController,
                        isTouchscreenModeActive = isTouchscreenModeActive,
                        onTouchGestureSettingsClick = onTouchGestureSettingsClick,
                        isShooterModeActive = isShooterModeActive,
                        onShooterModeSettingsClick = onShooterModeSettingsClick,
                        activeToggleIds = activeToggleIds,
                        isLsfgAvailable = isLsfgAvailable,
                        lsfgMultiplier = lsfgMultiplier,
                        lsfgFlowScale = lsfgFlowScale,
                        lsfgPerformanceMode = lsfgPerformanceMode,
                        onLsfgMultiplierChanged = onLsfgMultiplierChanged,
                        onLsfgFlowScaleChanged = onLsfgFlowScaleChanged,
                        onLsfgPerformanceModeChanged = onLsfgPerformanceModeChanged,
                        // Visual visibility is permanent on the second
                        // screen; pause/resume follows input activation and
                        // is reported by the outer effect below.
                        onAnimationComplete = {},
                        onRequestOpen = onRequestOpen,
                        fullScreen = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            ))
        }

        // The panel no longer opens/closes visually, so report only activation
        // changes. This keeps the game running while the menu is merely visible.
        val reportedActive = remember { mutableStateOf(false) }
        val presentationActive = DsHomeSecondScreen.presentationActive
        LaunchedEffect(isVisible, presentationActive) {
            if (presentationActive && reportedActive.value != isVisible) {
                onAnimationComplete(isVisible)
                reportedActive.value = isVisible
            }
        }

        // Leaving the game while the menu is open would otherwise leave a stale
        // QUICK_MENU model (with callbacks into a disposed composition) on the
        // second display. Clear it.
        DisposableEffect(hasExternalDisplay) {
            onDispose {
                DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.GAME)
            }
        }
    }

    // Keep the invisible main-display composition alive so invite polling can
    // open the passive second-screen menu. If Presentation.show() fails, this
    // same instance remains the fully usable visible fallback.
    if (!hasExternalDisplay || !DsHomeSecondScreen.presentationActive || !isVisible) {
        QuickMenu(
            isVisible = isVisible,
            onDismiss = onDismiss,
            onItemSelected = onItemSelected,
            renderer = renderer,
            glRenderer = glRenderer,
            container = container,
            wineProcesses = wineProcesses,
            isWineProcessesLoading = isWineProcessesLoading,
            onToolsVisibilityChanged = onToolsVisibilityChanged,
            onEndWineProcess = onEndWineProcess,
            isPerformanceHudEnabled = isPerformanceHudEnabled,
            performanceHudConfig = performanceHudConfig,
            fpsLimiterEnabled = fpsLimiterEnabled,
            fpsLimiterTarget = fpsLimiterTarget,
            fpsLimiterMax = fpsLimiterMax,
            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
            onFpsLimiterChanged = onFpsLimiterChanged,
            hasPhysicalController = hasPhysicalController,
            isTouchscreenModeActive = isTouchscreenModeActive,
            onTouchGestureSettingsClick = onTouchGestureSettingsClick,
            isShooterModeActive = isShooterModeActive,
            onShooterModeSettingsClick = onShooterModeSettingsClick,
            activeToggleIds = activeToggleIds,
            isLsfgAvailable = isLsfgAvailable,
            lsfgMultiplier = lsfgMultiplier,
            lsfgFlowScale = lsfgFlowScale,
            lsfgPerformanceMode = lsfgPerformanceMode,
            onLsfgMultiplierChanged = onLsfgMultiplierChanged,
            onLsfgFlowScaleChanged = onLsfgFlowScaleChanged,
            onLsfgPerformanceModeChanged = onLsfgPerformanceModeChanged,
            onAnimationComplete = { visible ->
                if (!hasExternalDisplay || !DsHomeSecondScreen.presentationActive) {
                    onAnimationComplete(visible)
                }
            },
            onRequestOpen = onRequestOpen,
            modifier = modifier,
        )
    }
}

/**
 * LIBRARY role: filters/sort/views quick surface. Reuses the options panel
 * content (sort, type/status filters, Steam collections) so library screens
 * share one implementation, rendered at the 40% quick-menu scrim.
 */
@Composable
private fun GcdsLibraryQuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    selectedFilters: EnumSet<AppFilter>,
    onFilterChanged: (AppFilter) -> Unit,
    currentSortOption: SortOption,
    onSortOptionChanged: (SortOption) -> Unit,
    steamCollections: List<SteamCollection>?,
    selectedSteamCollectionIds: Set<String>,
    steamCollectionCounts: Map<String, Int>,
    skippedDynamicCollections: Boolean,
    isSteamConnected: Boolean,
    isOffline: Boolean,
    onSteamCollectionToggle: (String) -> Unit,
    onClearSteamCollections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LibraryOptionsPanel(
        isOpen = isVisible,
        onDismiss = onDismiss,
        selectedFilters = selectedFilters,
        onFilterChanged = onFilterChanged,
        currentSortOption = currentSortOption,
        onSortOptionChanged = onSortOptionChanged,
        steamCollections = steamCollections,
        selectedSteamCollectionIds = selectedSteamCollectionIds,
        steamCollectionCounts = steamCollectionCounts,
        skippedDynamicCollections = skippedDynamicCollections,
        isSteamConnected = isSteamConnected,
        isOffline = isOffline,
        onSteamCollectionToggle = onSteamCollectionToggle,
        onClearSteamCollections = onClearSteamCollections,
        modifier = modifier,
        scrimAlpha = QUICK_MENU_SCRIM_ALPHA,
    )
}

/**
 * DOWNLOADS role: queue controls surface — pause/resume/cancel all and clear
 * finished, with live queue counts.
 */
@Composable
private fun GcdsDownloadsQuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    state: DownloadsState,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(state) { state.downloads.values.toList() }
    val activeCount = items.count { it.canPause }
    val resumableCount = items.count { it.canResume }
    val finishedCount = items.count { it.isFinished }
    val initialAction = remember(activeCount, resumableCount, items.size, finishedCount) {
        when {
            activeCount > 0 -> GcdsQueueAction.PAUSE_ALL
            resumableCount > 0 -> GcdsQueueAction.RESUME_ALL
            items.isNotEmpty() -> GcdsQueueAction.CANCEL_ALL
            finishedCount > 0 -> GcdsQueueAction.CLEAR_FINISHED
            else -> null
        }
    }

    ConsoleSidePanel(
        isOpen = isVisible,
        onDismiss = onDismiss,
        modifier = modifier,
        scrimAlpha = QUICK_MENU_SCRIM_ALPHA,
    ) { firstItemFocusRequester ->
        ConsolePanelHeader(
            title = stringResource(R.string.app_downloads),
            onBack = onDismiss,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val pauseAllFocused = remember { MutableInteractionSource() }
            val pauseAllSelected by pauseAllFocused.collectIsFocusedAsState()
            GcdsQueueActionRow(
                icon = Icons.Default.Pause,
                label = stringResource(R.string.downloads_pause_all),
                emphasized = activeCount > 0,
                enabled = activeCount > 0,
                focusRequester = if (initialAction == GcdsQueueAction.PAUSE_ALL) firstItemFocusRequester else remember { FocusRequester() },
                interactionSource = pauseAllFocused,
                isFocused = pauseAllSelected,
                onClick = onPauseAll,
            )
            GcdsQueueActionRow(
                icon = Icons.Default.PlayArrow,
                label = stringResource(R.string.downloads_resume_all),
                emphasized = resumableCount > 0,
                enabled = resumableCount > 0,
                focusRequester = if (initialAction == GcdsQueueAction.RESUME_ALL) firstItemFocusRequester else remember { FocusRequester() },
                onClick = onResumeAll,
            )
            GcdsQueueActionRow(
                icon = Icons.Default.Close,
                label = stringResource(R.string.downloads_cancel_all),
                emphasized = items.isNotEmpty(),
                enabled = items.isNotEmpty(),
                focusRequester = if (initialAction == GcdsQueueAction.CANCEL_ALL) firstItemFocusRequester else remember { FocusRequester() },
                onClick = onCancelAll,
            )
            GcdsQueueActionRow(
                icon = Icons.Default.DeleteSweep,
                label = stringResource(R.string.downloads_clear_finished),
                emphasized = finishedCount > 0,
                enabled = finishedCount > 0,
                focusRequester = if (initialAction == GcdsQueueAction.CLEAR_FINISHED) firstItemFocusRequester else remember { FocusRequester() },
                onClick = onClearFinished,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        ConsolePanelBackHint()
    }
}

private enum class GcdsQueueAction {
    PAUSE_ALL,
    RESUME_ALL,
    CANCEL_ALL,
    CLEAR_FINISHED,
}

/**
 * Shared action row for the downloads queue panel. Mirrors [ConsoleMenuActionItem]
 * but carries a disabled state and per-row interaction source so focus and
 * enabledness stay in sync.
 */
@Composable
private fun GcdsQueueActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    isFocused: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .gcdsFocusRing(interactionSource, RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Color.Transparent
                    isFocused -> MaterialTheme.colorScheme.primaryContainer
                    emphasized -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
                RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .focusRequester(focusRequester)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer
            else if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isFocused || (emphasized && enabled)) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer
            else if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

/** Quick-menu scrim: 40% (system overlays use 60%). */
private const val QUICK_MENU_SCRIM_ALPHA = 0.40f
