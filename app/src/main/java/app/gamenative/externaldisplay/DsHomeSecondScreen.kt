package app.gamenative.externaldisplay

import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Display
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.gamenative.PrefManager
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.LibraryItem
import app.gamenative.enums.AppTheme
import app.gamenative.ui.component.CompatibilityBadge
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.GameStatsRow
import app.gamenative.ui.component.dialog.LocalSecondScreenDialogWindowToken
import app.gamenative.ui.component.dialog.LocalSecondScreenDialogWindowType
import app.gamenative.ui.data.GameCardStats
import app.gamenative.ui.data.InstallProgress
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.shouldLoadNextLibraryPage
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.screen.library.components.DsGameGrid
import app.gamenative.ui.screen.library.components.DsContentBottomPadding
import app.gamenative.ui.screen.library.components.dsHomeCellMinSize
import app.gamenative.ui.screen.library.components.AppItem
import app.gamenative.ui.screen.library.components.GameSourceIcon
import app.gamenative.ui.screen.library.components.GridImageUrls
import app.gamenative.ui.screen.library.components.LibraryDynamicBackdrop
import app.gamenative.ui.screen.library.components.getGridImageUrl
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.isReduceMotionEnabled
import app.gamenative.ui.widget.PerformanceHudView
import app.gamenative.utils.rememberExternalDisplay
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import timber.log.Timber

private const val CONTROLLER_AXIS_REPEAT_MS = 220L
private const val COVER_FLOW_MAX_TILT_DEGREES = 12f

/**
 * Bridge between the main-screen library state and the second-display
 * presentation. The main screen publishes the model; the presentation's own
 * composition subscribes to it.
 */
object DsHomeSecondScreen {

    enum class Owner {
        LIBRARY,
        SYSTEM,
        GAME_CARD,
        SETTINGS,
        DIALOG,
        GAME,
    }

    enum class Mode {
        // Icon grid, navigable with the gamepad (DS_HOME and grid panes).
        GRID,

        // Passive details panel for the focused game (carousel/hero panes).
        DETAILS,

        // Full game-card content (the part below the hero) rendered by the
        // main display, shown on the second display while a card is open.
        CARD,

        // The in-game QuickMenu rendered on the second display while a game is
        // running and actively controlled by the gamepad.
        QUICK_MENU,

        // The same QuickMenu remains visible while input stays with the game.
        // Back promotes it to QUICK_MENU; Back again returns to this mode.
        QUICK_MENU_PASSIVE,

        // Passive, glanceable runtime status while the game owns controller focus.
        GAME_DASHBOARD,

        // Full settings workspace rendered on the lower display.
        SETTINGS,
    }

    /** The settings rail uses LB/RB, leaving D-pad to walk setting rows. */
    enum class ControllerNavigation {
        SPATIAL,
        VERTICAL_LIST,
    }

    class Model(
        val owner: Owner,
        val mode: Mode = Mode.GRID,
        val items: List<LibraryItem> = emptyList(),
        val focusedIndex: Int = 0,
        val focusedItem: LibraryItem? = null,
        val focusedStats: GameCardStats? = null,
        val focusedCompat: GameCompatibilityStatus? = null,
        val installProgressByAppId: Map<String, InstallProgress> = emptyMap(),
        val libraryLayout: PaneType = PaneType.GRID_CAPSULE,
        val currentTab: LibraryTab = LibraryTab.ALL,
        val isLoading: Boolean = false,
        val isSearching: Boolean = false,
        val searchQuery: String = "",
        val totalItemCount: Int = items.size,
        val currentPage: Int = 1,
        val lastPage: Int = 1,
        val tabCounts: Map<LibraryTab, Int> = emptyMap(),
        val onSearchQuery: (String) -> Unit = {},
        val onSearchToggle: () -> Unit = {},
        val onPreviousTab: (() -> Unit)? = null,
        val onNextTab: (() -> Unit)? = null,
        val controllerNavigation: ControllerNavigation = ControllerNavigation.SPATIAL,
        /** Direct physical-controller action for a companion workspace. */
        val onControllerKey: (Int) -> Boolean = { false },
        val onOptions: () -> Unit = {},
        val onSystemMenu: () -> Unit = {},
        val onOpenSettings: () -> Unit = {},
        val onAddGame: () -> Unit = {},
        val onQuickActions: () -> Unit = {},
        val onLayoutCycle: () -> Unit = {},
        val onRefresh: () -> Unit = {},
        val onPageChange: (Int) -> Unit = {},
        val cardContent: (@Composable () -> Unit)? = null,
        val menuContent: (@Composable () -> Unit)? = null,
        val settingsContent: (@Composable () -> Unit)? = null,
        val dashboardTitle: String = "",
        val dashboardSubtitle: String = "",
        val dashboardImageUrl: String = "",
        val dashboardLogoUrl: String = "",
        val performanceHudEnabled: Boolean = false,
        val performanceHudConfig: PerformanceHudConfig = PerformanceHudConfig(),
        val performanceHudFpsProvider: () -> Float = { 0f },
        val performanceHudKey: Int = 0,
        /** Content requests its own stable focus target after a window handoff. */
        val managesControllerFocus: Boolean = false,
        /**
         * Stable identity of the current interactive page inside an owner.
         *
         * A settings workspace can replace its page without changing the
         * owner or mode. In that case the Presentation still needs a fresh
         * focus handoff, otherwise the physical controller remains attached
         * to a node that has just left the composition.
         */
        val focusRequestKey: Any? = null,
        val onShowDashboard: () -> Unit = {},
        val onShowMenu: () -> Unit = {},
        val onBack: () -> Unit = {},
        val onNavigate: (String) -> Unit = {},
        val onFocused: (Int) -> Unit = {},
    )

    private val models = mutableMapOf<Owner, Model>()
    private var activePresentation: DsHomePresentation? = null
    private var libraryControllerKeyHandler: ((Int) -> Boolean)? = null
    private var companionFocusKeyHandler: ((Int) -> Boolean)? = null

    var model by mutableStateOf<Model?>(null)
        private set

    /** True only after the Presentation window was shown successfully. */
    var presentationActive by mutableStateOf(false)
        private set

    /** Window token of the shown Presentation window, used to bind second-screen dialogs. */
    var presentationWindowToken: IBinder? by mutableStateOf(null)
        private set

    /** Window type of the shown Presentation window (TYPE_PRESENTATION, hidden in the SDK). */
    var presentationWindowType: Int? by mutableStateOf(null)
        private set

    /**
     * Changes whenever the companion window needs Compose to restore a controller target.
     * A FocusRequester can only succeed after Android has assigned focus to the Presentation
     * window, so this bridges that window-level event into the composition.
     */
    var controllerFocusEpoch by mutableIntStateOf(0)
        private set

    fun markPresentationActive(active: Boolean) {
        presentationActive = active
    }

    fun publishPresentationWindow(token: IBinder?, type: Int?) {
        Timber.d("publishPresentationWindow token=$token type=$type (was token=$presentationWindowToken type=$presentationWindowType)")
        // Skip the Compose-state write when the value did not change; the
        // post-attached republish from DsHomePresentationHost used to fire
        // twice (once on show, once after the window token became available)
        // and each write triggered a recomposition of every consumer of the
        // second-screen dialog locals. Dialogs that were already open would
        // re-evaluate secondScreenDialogProperties() and flash.
        if (presentationWindowToken == token && presentationWindowType == type) return
        presentationWindowToken = token
        presentationWindowType = type
    }

    fun requestControllerFocus() {
        controllerFocusEpoch++
    }

    internal fun attachPresentation(presentation: DsHomePresentation) {
        activePresentation = presentation
    }

    internal fun detachPresentation(presentation: DsHomePresentation) {
        if (activePresentation === presentation) activePresentation = null
    }

    internal fun attachLibraryControllerKeyHandler(handler: (Int) -> Boolean) {
        libraryControllerKeyHandler = handler
    }

    internal fun detachLibraryControllerKeyHandler(handler: (Int) -> Boolean) {
        if (libraryControllerKeyHandler === handler) libraryControllerKeyHandler = null
    }

    internal fun dispatchLibraryControllerKey(keyCode: Int): Boolean =
        libraryControllerKeyHandler?.invoke(keyCode) == true

    /**
     * Controller navigation for every non-library companion workspace.
     * Library owns a separate index-based handler; settings and cards instead
     * route directly into Compose's FocusManager through this bridge.
     */
    internal fun attachCompanionFocusKeyHandler(handler: (Int) -> Boolean) {
        companionFocusKeyHandler = handler
    }

    internal fun detachCompanionFocusKeyHandler(handler: (Int) -> Boolean) {
        if (companionFocusKeyHandler === handler) companionFocusKeyHandler = null
    }

    internal fun dispatchCompanionFocusKey(keyCode: Int): Boolean {
        return companionFocusKeyHandler?.invoke(keyCode) == true
    }

    /**
     * Android delivers hardware controller events to the activity window, not
     * reliably to a secondary [Presentation]. Forward them to the companion
     * Compose tree only while that tree owns controller focus.
     */
    fun dispatchControllerKeyEvent(event: KeyEvent): Boolean {
        if (model?.mode?.ownsControllerFocus() != true || !event.isCompanionControllerKey()) return false
        return activePresentation?.dispatchControllerKeyEvent(event) ?: false
    }

    fun dispatchControllerMotionEvent(event: MotionEvent?): Boolean {
        if (event == null || model?.mode?.ownsControllerFocus() != true || !event.isCompanionControllerMotion()) return false
        return activePresentation?.dispatchControllerMotionEvent(event) ?: false
    }

    fun publish(model: Model) {
        models[model.owner] = model
        updateActiveModel()
    }

    fun clear(owner: Owner) {
        models.remove(owner)
        updateActiveModel()
    }

    fun clearAll() {
        models.clear()
        model = null
    }

    /** Restore a deterministic first focus target whenever the active menu layer changes. */
    private fun updateActiveModel() {
        val previous = model
        val next = models.values.maxByOrNull { secondScreenOwnerPriority(it.owner) }
        model = next
        if (next?.mode?.ownsControllerFocus() == true &&
            (previous?.owner != next.owner ||
                previous.mode != next.mode ||
                previous.focusRequestKey != next.focusRequestKey)
        ) {
            requestControllerFocus()
        }
    }

}

internal fun secondScreenOwnerPriority(owner: DsHomeSecondScreen.Owner): Int = when (owner) {
    DsHomeSecondScreen.Owner.LIBRARY -> 0
    DsHomeSecondScreen.Owner.SYSTEM -> 1
    // Settings opened from a game card must replace that card as the active
    // lower-screen workspace, rather than remaining underneath it.
    DsHomeSecondScreen.Owner.SETTINGS -> 4
    DsHomeSecondScreen.Owner.GAME_CARD -> 3
    DsHomeSecondScreen.Owner.GAME -> 5
    // A dialog is always the topmost interaction layer, including while GAME
    // owns the persistent dashboard / quick menu below it.
    DsHomeSecondScreen.Owner.DIALOG -> 6
}

internal fun KeyEvent.isCompanionControllerKey(): Boolean = keyCode.isCompanionControllerKey()

internal fun Int.isCompanionControllerKey(): Boolean = when (this) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_START,
    -> true

    else -> false
}

/** Compose SettingsTile/clickable consistently activates from ENTER on this runtime. */
internal fun normalizedCompanionComposeKeyCode(keyCode: Int): Int = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_ENTER
    else -> keyCode
}

internal fun companionTabAction(
    keyCode: Int,
    onPreviousTab: (() -> Unit)?,
    onNextTab: (() -> Unit)?,
): (() -> Unit)? = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_L2,
    -> onPreviousTab

    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_R2,
    -> onNextTab

    else -> null
}

private fun KeyEvent.withKeyCode(keyCode: Int): KeyEvent = KeyEvent(
    downTime,
    eventTime,
    action,
    keyCode,
    repeatCount,
    metaState,
    deviceId,
    scanCode,
    flags,
    source,
)

private fun KeyEvent.withAction(action: Int): KeyEvent = KeyEvent(
    downTime,
    eventTime,
    action,
    keyCode,
    repeatCount,
    metaState,
    deviceId,
    scanCode,
    flags,
    source,
)

/** Keys that Compose can safely replay after the companion focus is restored. */
internal fun Int.isCompanionComposeNavigationKey(): Boolean = when (this) {
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    -> true

    else -> false
}

internal fun nextLibraryControllerIndex(
    currentIndex: Int,
    itemCount: Int,
    keyCode: Int,
    navigationMode: Int,
    gridColumns: Int,
): Int? {
    if (itemCount <= 0) return null
    val current = currentIndex.coerceIn(0, itemCount - 1)
    val columns = gridColumns.coerceAtLeast(1)
    val target = when (navigationMode) {
        0 -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (current % columns > 0) current - 1 else current
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (current % columns < columns - 1) current + 1 else current
            KeyEvent.KEYCODE_DPAD_UP -> current - columns
            KeyEvent.KEYCODE_DPAD_DOWN -> current + columns
            else -> return null
        }
        2 -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> current - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> current + 1
            else -> return null
        }
        else -> when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> current - 1
            KeyEvent.KEYCODE_DPAD_DOWN -> current + 1
            else -> return null
        }
    }
    return target.coerceIn(0, itemCount - 1)
}

private fun MotionEvent.isCompanionControllerMotion(): Boolean =
    isCompanionControllerMotion(
        source = source,
        hatX = getAxisValue(MotionEvent.AXIS_HAT_X),
        hatY = getAxisValue(MotionEvent.AXIS_HAT_Y),
    )

/**
 * AYN's built-in Xbox device can expose ABS_HAT motion with an OEM source that
 * does not contain Android's standard GAMEPAD/JOYSTICK bits. Non-zero HAT axes
 * are unambiguous controller navigation and must not be discarded.
 */
internal fun isCompanionControllerMotion(source: Int, hatX: Float, hatY: Float): Boolean =
    source and (InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD) != 0 ||
        abs(hatX) >= 0.5f || abs(hatY) >= 0.5f

/** Only interactive companion modes may own physical-controller focus. */
internal fun DsHomeSecondScreen.Mode.ownsControllerFocus(): Boolean = when (this) {
    DsHomeSecondScreen.Mode.GRID,
    DsHomeSecondScreen.Mode.CARD,
    DsHomeSecondScreen.Mode.SETTINGS,
    -> true

    DsHomeSecondScreen.Mode.DETAILS,
    DsHomeSecondScreen.Mode.QUICK_MENU,
    DsHomeSecondScreen.Mode.QUICK_MENU_PASSIVE,
    DsHomeSecondScreen.Mode.GAME_DASHBOARD,
    -> false
}

private class PresentationLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    fun handlePresentationStart() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun handlePresentationStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

class DsHomePresentation(
    private val hostContext: Context,
    display: Display,
) : Presentation(hostContext, display) {

    private val presentationLifecycleOwner = PresentationLifecycleOwner()
    private lateinit var composeView: ComposeView
    private var composeWindowContext: Context? = null
    private var lastHorizontalAxisDirection = 0
    private var lastVerticalAxisDirection = 0
    private var lastHorizontalAxisDispatchTime = 0L
    private var lastVerticalAxisDispatchTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DsHomeSecondScreen.attachPresentation(this)
        // Match the host Surface background so the Presentation window does not
        // flash its default (black) window background between show() and the
        // Compose tree drawing its first frame.
        window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK),
        )
        updateInputMode(DsHomeSecondScreen.model?.mode ?: DsHomeSecondScreen.Mode.DETAILS)
        // A Presentation window is TYPE_PRESENTATION (2037). Compose Dialog()
        // creates its own window; its LayoutParams type (2037 via
        // secondScreenDialogProperties) must match the dialog context window type,
        // otherwise Android 13+ throws "Window type mismatch". Build the
        // ComposeView on a TYPE_PRESENTATION window context bound to the same
        // display so dialogs opened from second-screen content (settings auth,
        // quick menu, etc.) attach to the presentation window without crashing.
        // `createWindowContext` takes the locale of the physical display. On
        // this device that is Russian, while Gameplay itself is configured for
        // English. Reapply the host Activity configuration after creating the
        // presentation window so stringResource() matches the app language.
        val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                hostContext.createWindowContext(
                    display,
                    window?.attributes?.type ?: 2037, // TYPE_PRESENTATION
                    null,
                )
            }.getOrElse { hostContext }
        } else {
            hostContext
        }
        composeWindowContext = windowContext
        val composeConfiguration = Configuration(windowContext.resources.configuration).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setLocales(hostContext.resources.configuration.locales)
            } else {
                @Suppress("DEPRECATION")
                setLocale(hostContext.resources.configuration.locale)
            }
        }
        val composeContext = windowContext.createConfigurationContext(composeConfiguration)
        composeView = ComposeView(composeContext).apply {
            setViewTreeLifecycleOwner(presentationLifecycleOwner)
            setViewTreeSavedStateRegistryOwner(presentationLifecycleOwner)
            setViewTreeOnBackPressedDispatcherOwner(presentationLifecycleOwner)
            setContent {
                // setViewTreeOnBackPressedDispatcherOwner only wires the ViewTree
                // owner; the Compose composition still needs the local explicitly,
                // otherwise nested BackHandlers (e.g. SteamAchievementsPage) throw
                // "No OnBackPressedDispatcherOwner was provided".
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides presentationLifecycleOwner,
                    LocalActivityResultRegistryOwner provides requireNotNull(
                        hostContext as? ActivityResultRegistryOwner,
                    ) { "Dual-screen presentation requires an ActivityResultRegistryOwner host" },
                ) {
                    val appTheme = PrefManager.appTheme
                    val isDark = when (appTheme) {
                        AppTheme.AUTO -> (context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                        AppTheme.DAY -> false
                        AppTheme.NIGHT, AppTheme.AMOLED -> true
                    }
                    PluviaTheme(
                        isDark = isDark,
                        isAmoled = appTheme == AppTheme.AMOLED,
                        style = PrefManager.appThemePalette,
                        customThemeJson = PrefManager.customThemeJson
                            .takeIf { PrefManager.customThemeEnabled },
                        reduceMotion = PrefManager.reduceMotion,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            CompositionLocalProvider(
                                LocalSecondScreenDialogWindowType provides DsHomeSecondScreen.presentationWindowType,
                                LocalSecondScreenDialogWindowToken provides DsHomeSecondScreen.presentationWindowToken,
                            ) {                                val model = DsHomeSecondScreen.model
                                if (model != null) {
                                    LaunchedEffect(model.mode) { updateInputMode(model.mode) }
                                    SecondScreenControllerFocusBridge(model) {
                                        when (model.mode) {
                                            DsHomeSecondScreen.Mode.GRID -> DsHomeSecondScreenGrid(model)
                                            DsHomeSecondScreen.Mode.DETAILS -> DsHomeSecondScreenDetails(model)
                                            DsHomeSecondScreen.Mode.CARD -> model.cardContent?.invoke()
                                            DsHomeSecondScreen.Mode.QUICK_MENU -> SecondScreenGamePanel(
                                                model = model,
                                                showMenu = true,
                                            )
                                            DsHomeSecondScreen.Mode.QUICK_MENU_PASSIVE -> model.menuContent?.invoke()
                                            DsHomeSecondScreen.Mode.GAME_DASHBOARD -> SecondScreenGamePanel(
                                                model = model,
                                                showMenu = false,
                                            )
                                            DsHomeSecondScreen.Mode.SETTINGS -> model.settingsContent?.invoke()
                                        }
                                    }
                                } else {
                                    LaunchedEffect(Unit) { updateInputMode(DsHomeSecondScreen.Mode.DETAILS) }
                                    // The lower display is ready as soon as it is attached.
                                    // Keep its background quiet until the library publishes content.
                                    Box(modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
        }
        setContentView(composeView)
        presentationLifecycleOwner.handlePresentationStart()
        if ((DsHomeSecondScreen.model?.mode ?: DsHomeSecondScreen.Mode.DETAILS).ownsControllerFocus()) {
            requestComposeControllerFocus()
        }
    }

    private fun updateInputMode(mode: DsHomeSecondScreen.Mode) {
        if (mode.ownsControllerFocus()) {
            // Library, game-card and settings surfaces are controller workspaces
            // on the lower display. They must own window focus for D-pad traversal
            // and A, including after the display wakes or is unfolded again.
            window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
            requestComposeControllerFocus()
        } else if (mode == DsHomeSecondScreen.Mode.GAME_DASHBOARD ||
            mode == DsHomeSecondScreen.Mode.QUICK_MENU
        ) {
            // In-game panels stay touchable on the lower screen while hardware
            // keys remain routed to the game on the upper display.
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        } else {
            // Passive details and the passive quick-menu stage never steal
            // controller input from the main display.
            window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            )
        }
    }

    private fun requestComposeControllerFocus() {
        if (!::composeView.isInitialized) return
        composeView.isFocusable = true
        composeView.isFocusableInTouchMode = true
        composeView.post {
            window?.decorView?.requestFocus()
            composeView.requestFocus()
            DsHomeSecondScreen.requestControllerFocus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && (DsHomeSecondScreen.model?.mode?.ownsControllerFocus() == true)) {
            requestComposeControllerFocus()
        }
    }

    /**
     * On a real external display Android gives the focused Presentation its own
     * input channel. In that case MainActivity.dispatchKeyEvent is never
     * reached, so the companion must route controller keys from its own window.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device?.name?.contains("Xbox", ignoreCase = true) == true) {
            Timber.d(
                "DS_INPUT key action=%d code=%d source=0x%s mode=%s",
                event.action,
                event.keyCode,
                event.source.toString(16),
                DsHomeSecondScreen.model?.mode,
            )
        }
        if (DsHomeSecondScreen.model?.mode?.ownsControllerFocus() == true &&
            event.isCompanionControllerKey()
        ) {
            dispatchControllerKeyEvent(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Thor's physical D-pad is reported by the Xbox input device as
     * ABS_HAT0X/ABS_HAT0Y, so Android delivers it as a MotionEvent rather than
     * KEYCODE_DPAD_*. While this Presentation owns the focused window those
     * events never pass through MainActivity; route them here at the actual
     * window boundary.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.device?.name?.contains("Xbox", ignoreCase = true) == true) {
            Timber.d(
                "DS_INPUT motion action=%d source=0x%s hat=(%.2f,%.2f) stick=(%.2f,%.2f) mode=%s owns=%s",
                event.action,
                event.source.toString(16),
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
                DsHomeSecondScreen.model?.mode,
                DsHomeSecondScreen.model?.mode?.ownsControllerFocus(),
            )
        }
        if (DsHomeSecondScreen.model?.mode?.ownsControllerFocus() == true &&
            event.isCompanionControllerMotion()
        ) {
            dispatchControllerMotionEvent(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    /** Called by [MainActivity] through [DsHomeSecondScreen] for controller input. */
    fun dispatchControllerKeyEvent(event: KeyEvent): Boolean {
        if (!::composeView.isInitialized || DsHomeSecondScreen.model?.mode?.ownsControllerFocus() != true) return false

        val currentModel = DsHomeSecondScreen.model
        if (currentModel?.mode == DsHomeSecondScreen.Mode.GRID && event.action == KeyEvent.ACTION_DOWN) {
            if (
                DsHomeSecondScreen.dispatchLibraryControllerKey(event.keyCode)
            ) {
                return true
            }
        }

        if (currentModel?.mode == DsHomeSecondScreen.Mode.SETTINGS) {
            val tabAction = companionTabAction(
                keyCode = event.keyCode,
                onPreviousTab = currentModel.onPreviousTab,
                onNextTab = currentModel.onNextTab,
            )
            if (tabAction != null) {
                if (event.action == KeyEvent.ACTION_DOWN) tabAction()
                return true
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B || event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Let the focused card/settings tree close its own top layer
                // first. BUTTON_B is normalized to Android BACK so Compose
                // BackHandlers and explicit key handlers share one contract.
                val backEvent = if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    event
                } else {
                    event.withKeyCode(KeyEvent.KEYCODE_BACK)
                }
                val handledInCompose = composeView.dispatchKeyEvent(backEvent)
                if (!handledInCompose && presentationLifecycleOwner.onBackPressedDispatcher.hasEnabledCallbacks()) {
                    presentationLifecycleOwner.onBackPressedDispatcher.onBackPressed()
                } else if (!handledInCompose) {
                    currentModel?.onBack()
                }
            }
            return true
        }

        val normalizedKeyCode = normalizedCompanionComposeKeyCode(event.keyCode)
        if (event.action == KeyEvent.ACTION_DOWN &&
            currentModel?.mode != DsHomeSecondScreen.Mode.GRID &&
            (currentModel?.onControllerKey?.invoke(normalizedKeyCode) == true ||
                DsHomeSecondScreen.dispatchCompanionFocusKey(normalizedKeyCode))
        ) {
            return true
        }
        val composeEvent = if (normalizedKeyCode == event.keyCode) {
            event
        } else {
            event.withKeyCode(normalizedKeyCode)
        }
        val handled = composeView.dispatchKeyEvent(composeEvent)
        if (!handled && event.action == KeyEvent.ACTION_DOWN &&
            normalizedKeyCode.isCompanionComposeNavigationKey()
        ) {
            // Compose can briefly lose its focused child while a card, nested
            // settings page or dialog is being replaced. Previously we still
            // consumed the gamepad event at this point, so A/D-pad appeared to
            // stop working until the user touched the lower display. Restore
            // the Presentation focus and replay a complete key gesture after
            // the focus graph has settled. The original event remains consumed
            // so it can never leak through to the game on the main display.
            Timber.d("Recovering companion focus for controller key=$normalizedKeyCode")
            requestComposeControllerFocus()
            composeView.postDelayed({
                val retryDown = composeEvent.withAction(KeyEvent.ACTION_DOWN)
                composeView.dispatchKeyEvent(retryDown)
                composeView.dispatchKeyEvent(KeyEvent.changeAction(retryDown, KeyEvent.ACTION_UP))
            }, 48L)
        }
        // Once an event is routed to the companion, do not also let the main
        // activity move its own focus graph or activate a second control.
        return true
    }

    fun dispatchControllerMotionEvent(event: MotionEvent): Boolean {
        if (!::composeView.isInitialized || DsHomeSecondScreen.model?.mode?.ownsControllerFocus() != true) return false
        val horizontal = dominantAxisValue(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_X),
        ).toControllerDirection()
        val vertical = dominantAxisValue(
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_Y),
        ).toControllerDirection()
        val directionDispatcher: (Int) -> Boolean = if (DsHomeSecondScreen.model?.mode == DsHomeSecondScreen.Mode.GRID) {
            DsHomeSecondScreen::dispatchLibraryControllerKey
        } else {
            { keyCode ->
                DsHomeSecondScreen.model?.onControllerKey?.invoke(keyCode) == true ||
                    DsHomeSecondScreen.dispatchCompanionFocusKey(keyCode) ||
                    dispatchComposeDirectionKey(event, keyCode)
            }
        }
        val horizontalHandled = dispatchAxisDirection(
            direction = horizontal,
            previousDirection = lastHorizontalAxisDirection,
            previousDispatchTime = lastHorizontalAxisDispatchTime,
            negativeKeyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            positiveKeyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            eventTime = event.eventTime,
            dispatch = directionDispatcher,
        )
        val verticalHandled = dispatchAxisDirection(
            direction = vertical,
            previousDirection = lastVerticalAxisDirection,
            previousDispatchTime = lastVerticalAxisDispatchTime,
            negativeKeyCode = KeyEvent.KEYCODE_DPAD_UP,
            positiveKeyCode = KeyEvent.KEYCODE_DPAD_DOWN,
            eventTime = event.eventTime,
            dispatch = directionDispatcher,
        )
        if (horizontal != lastHorizontalAxisDirection) lastHorizontalAxisDirection = horizontal
        if (vertical != lastVerticalAxisDirection) lastVerticalAxisDirection = vertical
        if (horizontalHandled) lastHorizontalAxisDispatchTime = event.eventTime
        if (verticalHandled) lastVerticalAxisDispatchTime = event.eventTime
        if (horizontalHandled || verticalHandled || horizontal != 0 || vertical != 0) return true
        composeView.dispatchGenericMotionEvent(event)
        return true
    }

    private fun dispatchComposeDirectionKey(event: MotionEvent, keyCode: Int): Boolean {
        val down = KeyEvent(
            event.downTime,
            event.eventTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            event.deviceId,
            0,
            0,
            event.source,
        )
        val handled = composeView.dispatchKeyEvent(down)
        composeView.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
        return handled
    }

    private fun dispatchAxisDirection(
        direction: Int,
        previousDirection: Int,
        previousDispatchTime: Long,
        negativeKeyCode: Int,
        positiveKeyCode: Int,
        eventTime: Long,
        dispatch: (Int) -> Boolean,
    ): Boolean {
        if (direction == 0) return false
        val directionChanged = direction != previousDirection
        val repeatDue = eventTime - previousDispatchTime >= CONTROLLER_AXIS_REPEAT_MS
        if (!directionChanged && !repeatDue) return false
        val keyCode = if (direction < 0) negativeKeyCode else positiveKeyCode
        return dispatch(keyCode)
    }

    // The physical back button on the second display must not dismiss the
    // Presentation itself. First let the Compose tree try to consume it (the
    // game card routes it to close the card -> back to library); if nothing
    // in the composition handled it, fall back to the OnBackPressedDispatcher
    // so in-menu BackHandlers (e.g. QuickMenu dismiss) work too. Consuming the
    // event also stops the Dialog's default dismiss.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val currentModel = DsHomeSecondScreen.model
        if (event.action == KeyEvent.ACTION_DOWN && currentModel?.mode == DsHomeSecondScreen.Mode.SETTINGS) {
            companionTabAction(
                keyCode = keyCode,
                onPreviousTab = currentModel.onPreviousTab,
                onNextTab = currentModel.onNextTab,
            )?.let {
                it()
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // Never short-circuit settings layers: the focused tree may have
                // enabled BackHandlers (drawer, search, unsaved-changes confirm)
                // that must run first. Fall back to the dispatcher, then to the
                // published model back, only when nothing in composition handled it.
                val backEvent = if (keyCode == KeyEvent.KEYCODE_BACK) {
                    event
                } else {
                    event.withKeyCode(KeyEvent.KEYCODE_BACK)
                }
                val handledInCompose = composeView.dispatchKeyEvent(backEvent)
                if (!handledInCompose) {
                    if (presentationLifecycleOwner.onBackPressedDispatcher.hasEnabledCallbacks()) {
                        presentationLifecycleOwner.onBackPressedDispatcher.onBackPressed()
                    } else {
                        currentModel?.onBack()
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        DsHomeSecondScreen.detachPresentation(this)
        presentationLifecycleOwner.handlePresentationStop()
        super.onDetachedFromWindow()
    }
}

@Composable
private fun DsHomeSecondScreenGrid(model: DsHomeSecondScreen.Model) {
    val gridState = rememberLazyGridState()
    val firstItemFocusRequester = remember { FocusRequester() }
    var scaleStep by remember { mutableStateOf(PrefManager.dsHomeIconScale) }
    var controllerSelectedIndex by remember(model.currentTab) {
        mutableIntStateOf(model.focusedIndex.coerceIn(0, model.items.lastIndex.coerceAtLeast(0)))
    }
    val cellMinSize = dsHomeCellMinSize(scaleStep, lowerDisplay = true)
    LaunchedEffect(model.currentTab) {
        gridState.scrollToItem(0)
    }
    LaunchedEffect(model.focusedIndex, model.items.isEmpty()) {
        if (model.focusedIndex in model.items.indices) {
            controllerSelectedIndex = model.focusedIndex
            if (PrefManager.dsHomeNavMode == 0) gridState.animateScrollToItem(model.focusedIndex)
        }
    }
    LaunchedEffect(gridState, model.items.size, model.totalItemCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (shouldLoadNextLibraryPage(lastVisibleIndex, model.items.size, model.totalItemCount)) {
                    model.onPageChange(1)
                }
            }
    }
    // The presentation window gains real focus a moment after show(); wait for
    // window focus before bootstrapping compose focus so the initial selection
    // sticks instead of being stolen back by the main display.
    val windowInfo = LocalWindowInfo.current
    val controllerFocusEpoch = DsHomeSecondScreen.controllerFocusEpoch
    LaunchedEffect(model.items.isEmpty(), model.currentTab, model.focusedIndex, controllerFocusEpoch) {
        if (model.items.isEmpty()) return@LaunchedEffect
        withTimeoutOrNull(2_000) {
            snapshotFlow { windowInfo.isWindowFocused }
                .filter { it }
                .first()
        }
        var retries = 0
        while (retries < 8) {
            val accepted = runCatching {
                firstItemFocusRequester.requestFocus()
            }.getOrDefault(false)
            if (accepted) {
                break
            }
            retries++
            delay(32)
        }
    }
    var navMode by remember { androidx.compose.runtime.mutableIntStateOf(PrefManager.dsHomeNavMode) }
    // The header layout button cycles grid -> list -> CoverFlow -> cover wall, persisted
    // separately from the main-display pane type so both screens keep their own layout.
    val cycleNavMode = {
        navMode = (navMode + 1) % 4
        PrefManager.dsHomeNavMode = navMode
    }
    DisposableEffect(model, navMode, scaleStep) {
        val handler: (Int) -> Boolean = { keyCode ->
            if (model.isSearching) false
            else when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_L2,
                -> {
                    model.onPreviousTab?.let {
                        it()
                        true
                    } ?: false
                }
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_R2,
                -> {
                    model.onNextTab?.let {
                        it()
                        true
                    } ?: false
                }
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    model.onOptions()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_START -> {
                    model.onSystemMenu()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_Y -> {
                    model.onSearchToggle()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_X -> {
                    model.onAddGame()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    model.onQuickActions()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    model.items.getOrNull(controllerSelectedIndex)?.let { model.onNavigate(it.appId) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                -> {
                    val gridColumns = gridState.layoutInfo.maxSpan.coerceAtLeast(1)
                    val nextIndex = nextLibraryControllerIndex(
                        currentIndex = controllerSelectedIndex,
                        itemCount = model.items.size,
                        keyCode = keyCode,
                        navigationMode = navMode,
                        gridColumns = gridColumns,
                    )
                    if (nextIndex != null && nextIndex != controllerSelectedIndex) {
                        controllerSelectedIndex = nextIndex
                        model.onFocused(nextIndex)
                    }
                    true
                }
                else -> false
            }
        }
        DsHomeSecondScreen.attachLibraryControllerKeyHandler(handler)
        onDispose { DsHomeSecondScreen.detachLibraryControllerKeyHandler(handler) }
    }
    val actions = listOf(
        GamepadAction(GamepadButton.A, app.gamenative.R.string.action_select),
        GamepadAction(GamepadButton.SELECT, app.gamenative.R.string.options, model.onOptions),
        GamepadAction(GamepadButton.Y, app.gamenative.R.string.search, model.onSearchToggle),
        GamepadAction(GamepadButton.X, app.gamenative.R.string.action_add_game, model.onAddGame),
        GamepadAction(GamepadButton.START, app.gamenative.R.string.system_hub_open, model.onSystemMenu),
        GamepadAction(GamepadButton.B, app.gamenative.R.string.menu, model.onQuickActions),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (model.isSearching) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonL1, Key.ButtonL2 -> {
                        model.onPreviousTab?.let {
                            it()
                            true
                        } ?: false
                    }
                    Key.ButtonR1, Key.ButtonR2 -> {
                        model.onNextTab?.let {
                            it()
                            true
                        } ?: false
                    }
                    Key.ButtonSelect -> {
                        model.onOptions()
                        true
                    }
                    Key.ButtonStart -> {
                        model.onSystemMenu()
                        true
                    }
                    Key.ButtonY -> {
                        model.onSearchToggle()
                        true
                    }
                    Key.ButtonX -> {
                        model.onAddGame()
                        true
                    }
                    Key.ButtonB -> {
                        model.onQuickActions()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DualLibraryHeader(
                model = model,
                onLayoutCycle = cycleNavMode,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    model.isLoading && model.items.isEmpty() -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    model.items.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = stringResource(app.gamenative.R.string.library_no_results),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = model.onRefresh) {
                                Text(stringResource(app.gamenative.R.string.action_refresh))
                            }
                        }
                    }
                    navMode == 1 -> DsHomeSecondScreenList(
                        model = model,
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                    navMode == 2 -> DsHomeCoverFlowPane(
                        model = model,
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                    navMode == 3 -> DsHomeCoverWallPane(
                        model = model,
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                    else -> DsGameGrid(
                        items = model.items,
                        installProgressByAppId = model.installProgressByAppId,
                        listState = gridState,
                        cellMinSize = cellMinSize,
                        focusTargetIndex = model.focusedIndex,
                        selectedIndex = controllerSelectedIndex,
                        firstItemFocusRequester = firstItemFocusRequester,
                        onFocusedIndexChanged = model.onFocused,
                        onNavigate = model.onNavigate,
                        onScaleCycle = {
                            scaleStep = (scaleStep + 1) % 3
                            PrefManager.dsHomeIconScale = scaleStep
                        },
                        showLabels = true,
                        cellAspectRatio = 0.76f,
                        preferSquareIcon = false,
                        labelStyle = MaterialTheme.typography.labelLarge,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = DsContentBottomPadding),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        GamepadActionBar(
            actions = actions,
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = !model.isSearching,
            forceVisible = true,
            compact = true,
            scale = 1.25f,
        )
    }
}

@Composable
private fun DualLibraryHeader(
    model: DsHomeSecondScreen.Model,
    onLayoutCycle: (() -> Unit)? = null,
) {
    val layoutCycle = onLayoutCycle ?: model.onLayoutCycle
    if (model.isSearching) {
        // Keep the text local: the model is republished from the main-display composition
        // on every keystroke, and a fully controlled field loses its IME connection and
        // resets mid-typing. Syncs back in only when the search session restarts.
        var localQuery by androidx.compose.runtime.saveable.rememberSaveable(model.isSearching) {
            androidx.compose.runtime.mutableStateOf(model.searchQuery)
        }
        OutlinedTextField(
            value = localQuery,
            onValueChange = {
                localQuery = it
                model.onSearchQuery(it)
            },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = model.onSearchToggle) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(app.gamenative.R.string.back))
                }
            },
            label = { Text(stringResource(app.gamenative.R.string.search)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = { model.onPreviousTab?.invoke() },
            enabled = model.onPreviousTab != null,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
        }
        val currentTabCount = model.tabCounts[model.currentTab] ?: model.totalItemCount
        Text(
            text = stringResource(
                app.gamenative.R.string.library_tab_with_count,
                stringResource(model.currentTab.labelResId),
                currentTabCount,
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        IconButton(
            onClick = { model.onNextTab?.invoke() },
            enabled = model.onNextTab != null,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        if (model.lastPage > 1) {
            Text(
                text = "${model.currentPage.coerceAtMost(model.lastPage)} / ${model.lastPage}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = model.onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(app.gamenative.R.string.action_refresh),
            )
        }
        IconButton(onClick = layoutCycle) {
            Icon(Icons.Default.GridView, contentDescription = stringResource(app.gamenative.R.string.ds_home_icon_size_action))
        }
        IconButton(onClick = model.onSystemMenu) {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = stringResource(app.gamenative.R.string.system_hub_open),
            )
        }
        IconButton(onClick = model.onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(app.gamenative.R.string.settings_title))
        }
    }
}

@Composable
private fun DsHomeSecondScreenList(
    model: DsHomeSecondScreen.Model,
    firstItemFocusRequester: FocusRequester,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(model.currentTab) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(model.focusedIndex) {
        if (model.focusedIndex in model.items.indices) {
            listState.animateScrollToItem(model.focusedIndex)
        }
    }
    LaunchedEffect(listState, model.items.size, model.totalItemCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (shouldLoadNextLibraryPage(lastVisibleIndex, model.items.size, model.totalItemCount)) {
                    model.onPageChange(1)
                }
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = DsContentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(model.items, key = { _, item -> item.appId }) { index, item ->
            DualLibraryListRow(
                item = item,
                selected = index == model.focusedIndex,
                onFocused = { model.onFocused(index) },
                onClick = { model.onNavigate(item.appId) },
                focusRequester = if (index == model.focusedIndex) firstItemFocusRequester else null,
            )
        }
    }
}

@Composable
private fun DualLibraryListRow(
    item: LibraryItem,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val imageUrls by produceState(initialValue = GridImageUrls("", ""), key1 = item.appId) {
        value = withContext(Dispatchers.IO) { getGridImageUrl(context, item, PaneType.GRID_CAPSULE) }
    }
    val imageUrl = imageUrls.primary.ifEmpty { imageUrls.fallback }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(shape)
            .background(
                if (selected || isFocused) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = item.name }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier
                .width(104.dp)
                .height(64.dp),
            shape = RoundedCornerShape(PluviaTheme.tokens.cornerSm),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            if (imageUrl.isNotEmpty()) {
                CoilImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop, contentDescription = null),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameSourceIcon(gameSource = item.gameSource, iconSize = 18, alignmentBoxSize = 24)
                if (item.sizeBytes > 0) {
                    Text(
                        text = formatBytes(item.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * The dual-screen alternative to the old hero-and-shelf layout. It keeps the
 * same spatial language as the main display's CoverFlow: the focused cover is
 * centered and enlarged, adjacent covers recede in perspective, and the hero
 * art becomes a quiet background rather than a competing top banner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DsHomeCoverFlowPane(
    model: DsHomeSecondScreen.Model,
    firstItemFocusRequester: FocusRequester,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduceMotion = isReduceMotionEnabled()

    LaunchedEffect(model.currentTab, model.focusedIndex) {
        if (model.items.isNotEmpty()) {
            listState.animateScrollToItem(model.focusedIndex.coerceIn(0, model.items.lastIndex))
        }
    }
    LaunchedEffect(listState, model.items.size, model.totalItemCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (shouldLoadNextLibraryPage(lastVisibleIndex, model.items.size, model.totalItemCount)) {
                    model.onPageChange(1)
                }
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardHeight = (maxHeight - 92.dp).coerceAtLeast(180.dp)
        val cardWidth = cardHeight * (2f / 3f)
        val overscan = cardWidth * 0.4f
        val slotWidth = cardWidth + overscan * 2
        val itemSpacing = cardWidth * -0.11f - overscan * 2
        val sidePadding = ((maxWidth - slotWidth) / 2).coerceAtLeast(0.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val itemSpacingPx = with(density) { itemSpacing.toPx() }
        val cardWidthPx = with(density) { cardWidth.toPx() }
        val cameraDistancePx = with(density) { 6.dp.toPx() }

        val centeredIndexState = remember(listState) {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                    abs(itemInfo.offset + itemInfo.size / 2f - viewportCenter)
                }?.index ?: -1
            }
        }

        fun currentTargetIndex(): Int = model.focusedIndex.coerceIn(0, model.items.lastIndex.coerceAtLeast(0))

        fun moveSelection(delta: Int) {
            if (model.items.isEmpty()) return
            val targetIndex = (currentTargetIndex() + delta).coerceIn(0, model.items.lastIndex)
            if (targetIndex == currentTargetIndex()) return
            scope.launch {
                model.onFocused(targetIndex)
                listState.animateScrollToItem(targetIndex)
                runCatching { firstItemFocusRequester.requestFocus() }
            }
        }

        var settledBackdropItem by remember { mutableStateOf<LibraryItem?>(null) }
        val currentItems by androidx.compose.runtime.rememberUpdatedState(model.items)
        LaunchedEffect(listState) {
            var lastSettledIndex = -1
            snapshotFlow { listState.isScrollInProgress to centeredIndexState.value }
                .collect { (isScrolling, centeredIndex) ->
                    if (!isScrolling && centeredIndex in currentItems.indices && centeredIndex != lastSettledIndex) {
                        lastSettledIndex = centeredIndex
                        settledBackdropItem = currentItems[centeredIndex]
                    }
                }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            moveSelection(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            moveSelection(1)
                            true
                        }
                        else -> false
                    }
                },
        ) {
            LibraryDynamicBackdrop(
                appInfo = settledBackdropItem ?: model.items.getOrNull(model.focusedIndex),
                imageRefreshCounter = 0L,
                modifier = Modifier.fillMaxSize(),
            )

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(start = sidePadding, end = sidePadding, bottom = DsContentBottomPadding),
            ) {
                    itemsIndexed(model.items, key = { _, item -> item.appId }) { index, item ->
                    val tiltInputState = remember(index) {
                        derivedStateOf {
                            val layoutInfo = listState.layoutInfo
                            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
                            val viewportSpan = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                                .toFloat().coerceAtLeast(1f)
                            val visibleItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                            val itemCenter = visibleItem?.let { it.offset + it.size / 2f } ?: viewportCenter
                            (itemCenter - viewportCenter) to viewportSpan
                        }
                    }

                    val appItemModifier = Modifier.then(
                        if (index == model.focusedIndex) Modifier.focusRequester(firstItemFocusRequester) else Modifier,
                    )
                    DsCoverFlowSlot(
                        listIndex = index,
                        centeredIndex = centeredIndexState,
                        modifier = Modifier
                            .width(slotWidth)
                            .height(cardHeight + 24.dp),
                    ) {
                        val (distanceFromCenter, viewportSpan) = tiltInputState.value
                        val normalizedDistance = (distanceFromCenter / viewportSpan).coerceIn(-1f, 1f)
                        val distanceInSteps = abs(distanceFromCenter) / (slotWidthPx + itemSpacingPx).coerceAtLeast(1f)
                        val direction = when {
                            normalizedDistance < -0.03f -> 1f
                            normalizedDistance > 0.03f -> -1f
                            else -> 0f
                        }
                        // Perspective tilt is decorative. Flat covers under reduced
                        // motion; otherwise a clamped, named tilt so the ring never
                        // becomes a 3D showpiece.
                        val tiltAngle = if (reduceMotion) {
                            0f
                        } else {
                            (COVER_FLOW_MAX_TILT_DEGREES * distanceInSteps.coerceAtMost(1.2f))
                                .coerceAtMost(COVER_FLOW_MAX_TILT_DEGREES)
                        }
                        val scale = when {
                            reduceMotion -> 1f
                            distanceInSteps <= 1f -> 1.04f + (0.91f - 1.04f) * distanceInSteps
                            distanceInSteps <= 2f -> 0.91f + (0.86f - 0.91f) * (distanceInSteps - 1f)
                            else -> 0.8f
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(cardWidth)
                                .height(cardHeight)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    rotationY = direction * tiltAngle
                                    translationX = if (direction == 0f) 0f else direction * cardWidthPx * 0.11f
                                    cameraDistance = cameraDistancePx
                                    clip = false
                                    compositingStrategy = CompositingStrategy.Offscreen
                                },
                        ) {
                            AppItem(
                                modifier = appItemModifier,
                                appInfo = item,
                                onClick = { model.onNavigate(item.appId) },
                                onFocus = { model.onFocused(index) },
                                paneType = PaneType.GRID_CAPSULE,
                                showFocusGlow = false,
                                enableFocusScale = false,
                                animateStats = false,
                            )
                        }
                    }
                }
            }
        }
    }

}

/**
 * Presentation focus arrives after its Compose tree is first built. Retry a
 * child-focus handoff whenever the window focus epoch changes so settings,
 * cards and nested menus receive D-pad/A events instead of leaving focus on
 * the ComposeView itself.
 */
@Composable
private fun SecondScreenControllerFocusBridge(
    model: DsHomeSecondScreen.Model,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val controllerFocusEpoch = DsHomeSecondScreen.controllerFocusEpoch
    // The Presentation's ComposeView can own Android window focus while no
    // Compose child is focused. In that state dispatchKeyEvent reaches the
    // tree, but D-pad has nowhere to start spatial navigation. Keep a real,
    // focusable anchor at the root of every controller-owned lower screen;
    // the bridge can then transfer focus deterministically to its first child.
    val controllerAnchor = remember { FocusRequester() }

    fun bootstrapInteractiveFocus(): Boolean {
        if (focusManager.moveFocus(FocusDirection.Next)) return true
        controllerAnchor.requestFocus()
        return focusManager.moveFocus(FocusDirection.Next)
    }

    DisposableEffect(model.owner, model.mode, model.focusRequestKey) {
        val handler: (Int) -> Boolean = { keyCode ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val direction = if (model.controllerNavigation == DsHomeSecondScreen.ControllerNavigation.VERTICAL_LIST) {
                        FocusDirection.Previous
                    } else {
                        FocusDirection.Up
                    }
                    val moved = focusManager.moveFocus(direction)
                    if (!moved) bootstrapInteractiveFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val direction = if (model.controllerNavigation == DsHomeSecondScreen.ControllerNavigation.VERTICAL_LIST) {
                        FocusDirection.Next
                    } else {
                        FocusDirection.Down
                    }
                    val moved = focusManager.moveFocus(direction)
                    if (!moved) bootstrapInteractiveFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val moved = focusManager.moveFocus(FocusDirection.Left)
                    if (!moved) bootstrapInteractiveFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val moved = focusManager.moveFocus(FocusDirection.Right)
                    if (!moved) bootstrapInteractiveFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> {
                    // Do not move focus here. A arrives after the user has
                    // selected a row; moving it first made A target the next
                    // row (or nothing) instead of activating the selection.
                    false
                }
                else -> false
            }
        }
        DsHomeSecondScreen.attachCompanionFocusKeyHandler(handler)
        onDispose { DsHomeSecondScreen.detachCompanionFocusKeyHandler(handler) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(controllerAnchor)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !model.mode.ownsControllerFocus()) {
                    return@onPreviewKeyEvent false
                }
                // Compose's spatial search can report a D-pad event as handled
                // without selecting a new child when rails, scroll containers
                // and popup anchors are nested. Driving FocusManager directly
                // gives every second-screen workspace the same deterministic
                // navigation contract.
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                    Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                    Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                    else -> false
                }
            },
    ) { content() }

    LaunchedEffect(
        model.owner,
        model.mode,
        model.focusRequestKey,
        model.managesControllerFocus,
        controllerFocusEpoch,
    ) {
        if (!model.mode.ownsControllerFocus() ||
            model.mode == DsHomeSecondScreen.Mode.GRID
        ) return@LaunchedEffect
        // Establish a Compose focus origin before asking FocusManager to find
        // the first interactive child. Without this anchor a secondary
        // Presentation has no starting node and D-pad appears inert even
        // though A/B key events are delivered correctly.
        repeat(8) {
            if (runCatching { controllerAnchor.requestFocus() }.getOrDefault(false)) {
                return@repeat
            }
            delay(32)
        }
        repeat(8) {
            if (focusManager.moveFocus(FocusDirection.Next)) return@LaunchedEffect
            delay(32)
        }
    }
}

internal fun dominantAxisValue(primary: Float, fallback: Float): Float =
    if (abs(primary) >= abs(fallback)) primary else fallback

internal fun Float.toControllerDirection(): Int = when {
    this <= -0.55f -> -1
    this >= 0.55f -> 1
    else -> 0
}

@Composable
private fun DsCoverFlowSlot(
    listIndex: Int,
    centeredIndex: androidx.compose.runtime.State<Int>,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val center = centeredIndex.value
    val steps = if (center >= 0) abs(listIndex - center) else 0
    val zOrder = if (steps == 0) 20f else (10f - steps).coerceAtLeast(0f)
    Box(modifier = modifier.zIndex(zOrder), content = content)
}

/**
 * Cover wall navigation: big wide hero rows one per game, art-first browsing for
 * the small display. Up/down walks the wall, focus drives the row art.
 */
@Composable
private fun DsHomeCoverWallPane(
    model: DsHomeSecondScreen.Model,
    firstItemFocusRequester: FocusRequester,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(model.currentTab) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(listState, model.items.size, model.totalItemCount) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (shouldLoadNextLibraryPage(lastVisibleIndex, model.items.size, model.totalItemCount)) {
                    model.onPageChange(1)
                }
            }
    }
    LaunchedEffect(model.focusedIndex) {
        if (model.focusedIndex >= 0 && model.focusedIndex < model.items.size) {
            listState.animateScrollToItem(model.focusedIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = DsContentBottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(model.items, key = { _, item -> item.appId }) { index, item ->
            DsCoverWallRow(
                item = item,
                onFocused = { model.onFocused(index) },
                onClick = { model.onNavigate(item.appId) },
                focusRequester = if (index == model.focusedIndex) firstItemFocusRequester else null,
            )
        }
    }
}

@Composable
private fun DsCoverWallRow(
    item: LibraryItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val imageUrls by produceState(initialValue = GridImageUrls("", ""), key1 = item.appId) {
        value = withContext(Dispatchers.IO) { getGridImageUrl(context, item, PaneType.GRID_HERO) }
    }
    val imageUrl = imageUrls.primary.ifEmpty { imageUrls.fallback }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.6f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = item.name }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        if (imageUrl.isNotEmpty()) {
            CoilImage(
                modifier = Modifier.fillMaxSize(),
                imageModel = { imageUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop, contentDescription = null),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    ),
                )
                .padding(start = 14.dp, end = 14.dp, top = 20.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GameSourceIcon(gameSource = item.gameSource, iconSize = 15, alignmentBoxSize = 22)
            }
        }
    }
}

/**
 * Passive details panel for the focused game, shown on the second display when
 * the main display hosts a carousel/hero pane.
 */@Composable
private fun DsHomeSecondScreenDetails(model: DsHomeSecondScreen.Model) {
    val item = model.focusedItem
    if (item == null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Gamepad,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(app.gamenative.R.string.second_screen_no_game_selected),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        return
    }
    val context = LocalContext.current
    val imageUrls by produceState(
        initialValue = GridImageUrls("", ""),
        key1 = item.appId,
    ) {
        value = withContext(Dispatchers.IO) {
            getGridImageUrl(context, item, PaneType.GRID_HERO)
        }
    }
    val imageUrl = imageUrls.primary.ifEmpty { imageUrls.fallback }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .clip(RoundedCornerShape(
                    bottomStart = PluviaTheme.tokens.cornerLg,
                    bottomEnd = PluviaTheme.tokens.cornerLg,
                )),
        ) {
            if (imageUrl.isNotEmpty()) {
                CoilImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.6f),
                            ),
                        ),
                    ),
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GameSourceIcon(
                    gameSource = item.gameSource,
                    iconSize = 18,
                    alignmentBoxSize = 26,
                )
                val compat = model.focusedCompat
                if (compat != null) {
                    CompatibilityBadge(
                        status = compat,
                        showLabel = true,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            GameStatsRow(
                stats = model.focusedStats,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                animate = false,
            )

            if (item.sizeBytes > 0) {
                Text(
                    text = formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    if (mb < 1.0) return "< 1 MB"
    if (mb < 1024.0) return String.format("%.1f MB", mb)
    return String.format("%.1f GB", mb / 1024.0)
}

@Composable
private fun SecondScreenGamePanel(
    model: DsHomeSecondScreen.Model,
    showMenu: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            InGamePanelTab(
                text = stringResource(app.gamenative.R.string.second_screen_tab_game),
                selected = !showMenu,
                onClick = model.onShowDashboard,
            )
            InGamePanelTab(
                text = stringResource(app.gamenative.R.string.quick_menu_title),
                selected = showMenu,
                onClick = model.onShowMenu,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (showMenu) {
                model.menuContent?.invoke()
            } else {
                SecondScreenGameDashboard(model)
            }
        }
    }
}

@Composable
private fun RowScope.InGamePanelTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(52.dp),
        shape = RoundedCornerShape(PluviaTheme.tokens.cornerLg),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SecondScreenGameDashboard(model: DsHomeSecondScreen.Model) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        shape = RoundedCornerShape(PluviaTheme.tokens.cornerLg),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .width(128.dp)
                    .height(184.dp),
                shape = RoundedCornerShape(PluviaTheme.tokens.cornerLg),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                if (model.dashboardImageUrl.isNotBlank()) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { model.dashboardImageUrl },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                            contentDescription = null,
                        ),
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Gamepad,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(app.gamenative.R.string.second_screen_game_running),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (model.dashboardLogoUrl.isNotBlank()) {
                    CoilImage(
                        imageModel = { model.dashboardLogoUrl },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Fit,
                            contentDescription = model.dashboardTitle,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(104.dp)
                            .padding(top = 8.dp),
                    )
                } else {
                    Text(
                        text = model.dashboardTitle.ifBlank {
                            stringResource(app.gamenative.R.string.second_screen_game_running)
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (model.dashboardSubtitle.isNotBlank()) {
                    Text(
                        text = model.dashboardSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (model.performanceHudEnabled) {
                    key(model.performanceHudKey) {
                        AndroidView(
                            factory = { context ->
                                PerformanceHudView(
                                    context = context,
                                    fpsProvider = model.performanceHudFpsProvider,
                                    initialConfig = model.performanceHudConfig,
                                    initialCompactMode = true,
                                )
                            },
                            update = { hud -> hud.setConfig(model.performanceHudConfig) },
                            modifier = Modifier
                                .padding(top = 18.dp)
                                .wrapContentSize(Alignment.CenterStart),
                        )
                    }
                }
            }
        }
    }
}

/** Keeps the external-display surface alive while the feature is enabled. */
@Composable
fun DsHomePresentationHost() {
    val context = LocalContext.current
    val secondScreenEnabled = app.gamenative.PrefManager.dualScreenLauncherState.value
    val display = rememberExternalDisplay()

    // When the master switch is turned off, drop any published model so the
    // presentation is dismissed and the second screen goes clean immediately.
    DisposableEffect(secondScreenEnabled) {
        if (!secondScreenEnabled) {
            DsHomeSecondScreen.clearAll()
        }
        onDispose { }
    }

    DisposableEffect(secondScreenEnabled, display?.displayId) {
        if (!secondScreenEnabled || display == null) {
            DsHomeSecondScreen.markPresentationActive(false)
            return@DisposableEffect onDispose { }
        }
        val presentation = DsHomePresentation(context, display)
        runCatching { presentation.show() }
            .onSuccess {
                DsHomeSecondScreen.markPresentationActive(true)
                DsHomeSecondScreen.publishPresentationWindow(
                    token = presentation.window?.decorView?.windowToken,
                    type = presentation.window?.attributes?.type,
                )
                // decorView.windowToken is null until the presentation window is
                // attached; re-publish once attached so dialogs get a valid token.
                // publishPresentationWindow short-circuits when the value did not
                // change, so this only fires a recomposition when the token was
                // actually null at show time and is now non-null.
                presentation.window?.decorView?.post {
                    DsHomeSecondScreen.publishPresentationWindow(
                        token = presentation.window?.decorView?.windowToken,
                        type = presentation.window?.attributes?.type,
                    )
                }
            }
            .onFailure { Timber.e(it, "Failed to show DS_HOME presentation") }
        onDispose {
            DsHomeSecondScreen.publishPresentationWindow(null, null)
            DsHomeSecondScreen.markPresentationActive(false)
            runCatching { presentation.dismiss() }
        }
    }
}
