package app.gamenative.ui.screen.library

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.text.format.Formatter
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.localgames.LocalGameImporter
import app.gamenative.localgames.LocalInstallerImporter
import app.gamenative.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import app.gamenative.ui.component.dialog.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.gamenative.PrefManager
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.LibraryActions
import app.gamenative.ui.component.DualScreenAmbientStage
import app.gamenative.ui.component.ConsoleStatusIndicators
import app.gamenative.ui.component.layers.LocalLayerController
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.LoadingDialog
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.libraryTabCounts
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.data.Achievement
import com.winlator.container.ContainerData
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.model.LibraryViewModel
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.library.components.LibraryCarouselPane
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.screen.library.components.LibraryCompactRowPane
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.screen.library.components.DsHeroCard
import app.gamenative.ui.screen.library.components.LibraryDsHomePane
import app.gamenative.ui.screen.library.components.LibraryDetailPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.ui.screen.library.components.RecommendationDisclosureDialog
import app.gamenative.ui.screen.library.components.LibraryOptionsPanel
import app.gamenative.ui.screen.library.components.LibrarySearchBar
import app.gamenative.ui.screen.library.components.LibrarySourceNotLoggedInSplash
import app.gamenative.ui.screen.library.components.LibraryStateSplash
import app.gamenative.ui.screen.library.components.LibraryTabBar
import app.gamenative.ui.screen.auth.AmazonOAuthActivity
import app.gamenative.ui.screen.auth.EpicOAuthActivity
import app.gamenative.ui.screen.auth.GOGOAuthActivity
import app.gamenative.ui.screen.library.components.DualSystemMenu
import app.gamenative.ui.screen.library.components.ConsoleImportPanel
import app.gamenative.ui.screen.library.components.LibraryQuickActionsPanel
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.PlatformAuthUiHelpers
import app.gamenative.ui.util.PlatformLogoutCallbacks
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.rememberHasExternalDisplay
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.PlatformOAuthHandlers
import app.gamenative.utils.SteamUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.SystemClock

internal enum class LibraryFocusTarget {
    CONTENT,
    ROOT,
}

internal fun libraryFocusTarget(itemCount: Int): LibraryFocusTarget =
    if (itemCount > 0) LibraryFocusTarget.CONTENT else LibraryFocusTarget.ROOT

internal fun shouldRestoreFocusAfterCompanionDetach(
    wasCompanionAttached: Boolean,
    isCompanionAttached: Boolean,
    hasSelectedItem: Boolean,
    hasBlockingOverlay: Boolean,
): Boolean = wasCompanionAttached &&
    !isCompanionAttached &&
    !hasSelectedItem &&
    !hasBlockingOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeLibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    mainViewModel: app.gamenative.ui.model.MainViewModel = hiltViewModel(),
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onDownloadsClick: () -> Unit = {},
    isOffline: Boolean = false,
    isSteamConnected: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requestedLibraryAppId by mainViewModel.requestedLibraryAppId.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LibraryScreenContent(
        state = state,
        requestedAppId = requestedLibraryAppId,
        onRequestedAppIdHandled = { mainViewModel.setRequestedLibraryAppId(null) },
        importState = importState,
        onImportCustomGame = viewModel::importCustomGame,
        listState = viewModel.listState,
        sheetState = sheetState,
        onFilterChanged = viewModel::onFilterChanged,
        onPageChange = viewModel::onPageChange,
        onModalBottomSheet = viewModel::onModalBottomSheet,
        onIsSearching = viewModel::onIsSearching,
        onSearchQuery = viewModel::onSearchQuery,
        onRefresh = viewModel::onRefresh,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onPlayWithDiagnostics = onPlayWithDiagnostics,
        onNavigateRoute = onNavigateRoute,
        onLogout = onLogout,
        onGoOnline = onGoOnline,
        onDownloadsClick = onDownloadsClick,
        onSourceToggle = viewModel::onSourceToggle,
        onAddCustomGameFolder = viewModel::addCustomGameFolder,
        onImportPortableExecutable = viewModel::importPortableExecutable,
        onImportInstaller = viewModel::importInstaller,
        onMarkInstallerLaunching = viewModel::markInstallerLaunching,
        resolveCrossStoreSibling = viewModel::resolveCrossStoreSibling,
        onSortOptionChanged = viewModel::onSortOptionChanged,
        onSteamCollectionToggle = viewModel::onSteamCollectionToggle,
        onClearSteamCollections = viewModel::onClearSteamCollections,
        onOptionsPanelToggle = viewModel::onOptionsPanelToggle,
        onTabChanged = viewModel::onTabChanged,
        onPreviousTab = viewModel::onPreviousTab,
        onNextTab = viewModel::onNextTab,
        isOffline = isOffline,
        isSteamConnected = isSteamConnected,
    )
}

private fun isGameControllerConnected(): Boolean =
    InputDevice.getDeviceIds().any { id ->
        val device = InputDevice.getDevice(id) ?: return@any false
        val sources = device.sources
        sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    state: LibraryState,
    requestedAppId: String? = null,
    onRequestedAppIdHandled: () -> Unit = {},
    listState: LazyGridState,
    sheetState: SheetState,
    importState: LibraryViewModel.CustomGameImportState = LibraryViewModel.CustomGameImportState(),
    onImportCustomGame: (Uri, Boolean) -> Unit = { _, _ -> },
    onFilterChanged: (AppFilter) -> Unit,
    onPageChange: (Int) -> Unit,
    onModalBottomSheet: (Boolean) -> Unit,
    onIsSearching: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onLogout: () -> Unit,
    onGoOnline: () -> Unit,
    onDownloadsClick: () -> Unit = {},
    onSourceToggle: (GameSource) -> Unit,
    onAddCustomGameFolder: (String) -> Unit,
    onImportPortableExecutable: (Uri, (LocalGameImporter.ImportResult) -> Unit) -> Unit,
    onImportInstaller: (Uri, (LocalInstallerImporter.ImportResult) -> Unit) -> Unit,
    onMarkInstallerLaunching: (String, (String) -> Unit, (String) -> Unit) -> Unit,
    resolveCrossStoreSibling: (String, GameSource) -> LibraryItem?,
    onSortOptionChanged: (SortOption) -> Unit,
    onSteamCollectionToggle: (String) -> Unit,
    onClearSteamCollections: () -> Unit,
    onOptionsPanelToggle: (Boolean) -> Unit,
    onTabChanged: (LibraryTab) -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    isOffline: Boolean = false,
    isSteamConnected: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
    val hasExternalDisplay = rememberHasExternalDisplay()

    val gogOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(GOGOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.gog_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleGogAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.gog_login_success_title))
                },
                onDialogClose = { },
            )
        }
    }

    val epicOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(EpicOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.epic_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleEpicAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.epic_login_success_title))
                },
                onDialogClose = { },
            )
        }
    }

    val amazonOAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != android.app.Activity.RESULT_OK) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        val code = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_AUTH_CODE)
        if (code == null) {
            val message = result.data?.getStringExtra(AmazonOAuthActivity.EXTRA_ERROR)
                ?: context.getString(R.string.amazon_login_cancel)
            SnackbarManager.show(message)
            return@rememberLauncherForActivityResult
        }
        lifecycleScope.launch {
            PlatformOAuthHandlers.handleAmazonAuthentication(
                context = context,
                authCode = code,
                coroutineScope = lifecycleScope,
                onLoadingChange = { },
                onError = { msg ->
                    if (msg != null) {
                        SnackbarManager.show(msg)
                    }
                },
                onSuccess = {
                    SnackbarManager.show(context.getString(R.string.amazon_login_success_title))
                },
                onDialogClose = { },
            )
        }
    }

    var selectedAppId by remember { mutableStateOf<String?>(null) }

    // Pinned shortcut in "open game page" mode: select the requested game.
    LaunchedEffect(requestedAppId) {
        if (requestedAppId != null) {
            selectedAppId = requestedAppId
            onRequestedAppIdHandled()
        }
    }

    val carouselListState = rememberLazyListState()
    val isViewWide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var currentPaneType by remember { mutableStateOf(PrefManager.libraryLayout) }
    val visibleTabs = LibraryTab.visibleEntries(context)

    LaunchedEffect(visibleTabs, state.currentTab) {
        if (state.currentTab !in visibleTabs) {
            onTabChanged(LibraryTab.INSTALLED)
        }
    }

    // Initialize layout if undecided
    LaunchedEffect(Unit) {
        if (currentPaneType == PaneType.UNDECIDED) {
            currentPaneType = if (isViewWide) PaneType.GRID_HERO else PaneType.GRID_CAPSULE
            PrefManager.libraryLayout = currentPaneType
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    val gridFirstItemFocusRequester = remember { FocusRequester() }
    val carouselFocusRequester = remember { FocusRequester() }
    var gridFocusTargetListIndex by remember { mutableIntStateOf(0) }
    var carouselFocusTargetListIndex by remember { mutableIntStateOf(0) }
    var pendingGridFocusRequest by remember { mutableStateOf(false) }
    var pendingCarouselFocusRequest by remember { mutableStateOf(false) }

    var isSystemMenuOpen = LocalLayerController.current?.isSystemOpen == true
    val layerController = LocalLayerController.current
    var isQuickActionsOpen by remember { mutableStateOf(false) }
    var pendingQuickPrimaryAppId by remember { mutableStateOf<String?>(null) }
    // Quick-action overlays: per-game container settings and achievements
    var quickContainerEdit by remember { mutableStateOf<Pair<LibraryItem, ContainerData>?>(null) }
    var quickAchievements by remember { mutableStateOf<Triple<LibraryItem, List<Achievement>, Map<String, Float>>?>(null) }
    var quickAchievementsLoading by remember { mutableStateOf<LibraryItem?>(null) }
    // Track previous overlay states to detect when they close
    var wasSystemMenuOpen by remember { mutableStateOf(false) }
    var wasQuickActionsOpen by remember { mutableStateOf(false) }
    var wasOptionsPanelOpen by remember { mutableStateOf(false) }
    // Keep a stable reference to the selected item so detail view doesn't disappear during list refresh/pagination.
    var selectedLibraryItem by remember { mutableStateOf<LibraryItem?>(null) }
    val filterFabExpanded by remember(currentPaneType, listState, carouselListState) {
        derivedStateOf {
            if (currentPaneType == PaneType.CAROUSEL) {
                carouselListState.firstVisibleItemIndex == 0
            } else {
                listState.firstVisibleItemIndex == 0
            }
        }
    }

    // Dialog state for add custom game prompt
    var showAddCustomGameDialog by remember { mutableStateOf(false) }
    var previousAppCount by remember { mutableIntStateOf(state.appInfoList.size) }
    var wasCompanionAttached by remember { mutableStateOf(hasExternalDisplay) }
    var controllerBootstrapNeeded by remember { mutableStateOf(true) }
    var rootHasFocus by remember { mutableStateOf(false) }
    // True while focus lives in the top tab bar. The delayed focus-restoration effects below must
    // not yank focus back to the grid when the user has moved up into the tab bar (the action
    // buttons would otherwise light up for ~100ms and then lose focus).
    var tabBarHasFocus by remember { mutableStateOf(false) }
    var lastBootstrapAtMs by remember { mutableLongStateOf(0L) }

    fun getContentLastIndex(): Int {
        return state.appInfoList.lastIndex.coerceAtLeast(0)
    }

    fun firstVisibleContentIndex(): Int {
        val lastIndex = getContentLastIndex()
        if (lastIndex < 0) return 0

        return if (currentPaneType == PaneType.CAROUSEL) {
            carouselListState.firstVisibleItemIndex.coerceIn(0, lastIndex)
        } else {
            listState.firstVisibleItemIndex.coerceIn(0, lastIndex)
        }
    }

    fun currentCarouselFocusTargetIndex(): Int {
        val lastIndex = getContentLastIndex()
        if (lastIndex < 0) return 0

        return carouselFocusTargetListIndex.coerceIn(0, lastIndex)
    }

    fun preferredContentFocusIndex(): Int =
        if (currentPaneType == PaneType.CAROUSEL) currentCarouselFocusTargetIndex() else firstVisibleContentIndex()

    val inputModeManager = LocalInputModeManager.current
    fun ensureKeyboardInputMode() {
        if (isGameControllerConnected()) {
            if (inputModeManager.inputMode != InputMode.Keyboard) {
                inputModeManager.requestInputMode(InputMode.Keyboard)
            }
        }
    }

    // Moved all state.appInfoList.isNotEmpty() checking to this function
    fun isListFocusable(): Boolean = state.appInfoList.isNotEmpty()

    fun requestGridFocusOrDefer() {
        if (!isListFocusable()) return
        ensureKeyboardInputMode()
        try {
            gridFirstItemFocusRequester.requestFocus()
            pendingGridFocusRequest = false
            lastBootstrapAtMs = SystemClock.uptimeMillis()
        } catch (_: IllegalStateException) {
            pendingGridFocusRequest = true
        }
    }

    fun requestCarouselFocusOrDefer(targetListIndex: Int = currentCarouselFocusTargetIndex()) {
        if (!isListFocusable()) return
        ensureKeyboardInputMode()
        carouselFocusTargetListIndex = targetListIndex.coerceIn(0, getContentLastIndex())
        try {
            carouselFocusRequester.requestFocus()
            pendingCarouselFocusRequest = false
            lastBootstrapAtMs = SystemClock.uptimeMillis()
        } catch (_: IllegalStateException) {
            pendingCarouselFocusRequest = true
        }
    }

    fun requestContentFocusOrDefer(targetListIndex: Int = preferredContentFocusIndex()) {
        if (!isListFocusable()) return
        if (currentPaneType == PaneType.CAROUSEL) {
            requestCarouselFocusOrDefer(targetListIndex)
        } else {
            gridFocusTargetListIndex = targetListIndex
            requestGridFocusOrDefer()
        }
    }

    fun requestRootFocusSafe() {
        ensureKeyboardInputMode()
        try {
            rootFocusRequester.requestFocus()
        } catch (_: IllegalStateException) {}
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val portableExecutablePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImportPortableExecutable(uri) { result ->
            val message = when (result) {
                is LocalGameImporter.ImportResult.Ready -> context.getString(
                    R.string.library_imported_game,
                    result.title,
                )
                is LocalGameImporter.ImportResult.Rejected -> result.reason
                is LocalGameImporter.ImportResult.Failed -> result.reason
            }
            SnackbarManager.show(message)
        }
    }

    val focusedLibraryItem = state.appInfoList.getOrNull(
        if (currentPaneType == PaneType.CAROUSEL) {
            carouselFocusTargetListIndex.coerceIn(0, getContentLastIndex())
        } else {
            gridFocusTargetListIndex.coerceIn(0, getContentLastIndex())
        },
    )

    val installerPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        onImportInstaller(uri) { result ->
            when (result) {
                is LocalInstallerImporter.ImportResult.Ready -> {
                    onMarkInstallerLaunching(
                        result.session.id,
                        { appId ->
                            SnackbarManager.show(
                                context.getString(
                                    R.string.library_starting_installer,
                                    result.session.title,
                                ),
                            )
                            onClickPlay(appId, false)
                        },
                        SnackbarManager::show,
                    )
                }
                is LocalInstallerImporter.ImportResult.Rejected -> SnackbarManager.show(result.reason)
                is LocalInstallerImporter.ImportResult.Failed -> SnackbarManager.show(result.reason)
                is LocalInstallerImporter.ImportResult.ReadyPortable -> {
                    SnackbarManager.show(context.getString(R.string.disc_image_portable_added, result.folderName))
                }
            }
        }
    }

    val folderPicker = rememberCustomGameFolderPicker(
        onPathSelected = { path ->
            // When a folder is selected via OpenDocumentTree, the user has already granted
            // URI permissions for that specific folder. We should verify we can access it
            // rather than checking for broad storage permissions.
            val folder = java.io.File(path)
            val canAccess = try {
                folder.exists() && (folder.isDirectory && folder.canRead())
            } catch (e: Exception) {
                false
            }

            // Only request permissions if we can't access the folder AND it's outside the sandbox
            // (folders selected via OpenDocumentTree should already be accessible)
            if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                requestPermissionsForPath(context, path, storagePermissionLauncher)
            }
            onAddCustomGameFolder(path)
        },
        onFailure = { message ->
            SnackbarManager.show(message)
        },
    )

    // Modern add path: import the picked folder into app-owned storage via the SAF grant,
    // since the map-in-place flow needs MANAGE_EXTERNAL_STORAGE
    var showModernImportDialog by remember { mutableStateOf(false) }
    var importRemoveOriginal by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            onImportCustomGame(uri, importRemoveOriginal)
        }
    }

    // Handle opening folder picker (with dialog check). Modern keeps the import panel
    // (installer/ISO/executable options); its folder branch routes to the SAF import flow
    val onAddCustomGameClick = {
        if (BuildConfig.MODERN_ANDROID || PrefManager.showAddCustomGameDialog) {
            showAddCustomGameDialog = true
        } else {
            folderPicker.launchPicker()
        }
    }

    BackHandler(enabled = isSystemMenuOpen) {
        layerController?.closeSystem()
    }

    BackHandler(enabled = state.isOptionsPanelOpen) {
        onOptionsPanelToggle(false)
    }

    BackHandler(enabled = state.isSearching && selectedAppId == null) {
        onIsSearching(false)
        onSearchQuery("")
    }

    BackHandler(selectedLibraryItem != null) {
        selectedAppId = null
        selectedLibraryItem = null
    }

    // Restore focus when returning from game detail (without reloading list)
    LaunchedEffect(selectedAppId) {
        if (selectedAppId != null) {
            controllerBootstrapNeeded = true
        }
        if (selectedAppId == null && !hasExternalDisplay) {
            // Brief delay to let the UI settle after transition
            kotlinx.coroutines.delay(100)
            // Restore focus to content area
            if (isListFocusable()) {
                requestContentFocusOrDefer()
            } else {
                requestRootFocusSafe()
            }
        }
    }

    // A fold/detach destroys the companion Presentation window. The selected index is
    // already shared through the model callback; explicitly reattach focus on the main
    // display so the next D-pad press continues at that item rather than a stale window.
    LaunchedEffect(hasExternalDisplay) {
        val hasBlockingOverlay = isSystemMenuOpen || isQuickActionsOpen || state.isOptionsPanelOpen || state.isSearching
        val shouldRestore = shouldRestoreFocusAfterCompanionDetach(
            wasCompanionAttached = wasCompanionAttached,
            isCompanionAttached = hasExternalDisplay,
            hasSelectedItem = selectedAppId != null,
            hasBlockingOverlay = hasBlockingOverlay,
        )
        wasCompanionAttached = hasExternalDisplay

        if (!shouldRestore) return@LaunchedEffect

        kotlinx.coroutines.delay(50)
        if (isListFocusable()) {
            val preservedIndex = if (currentPaneType == PaneType.CAROUSEL) {
                currentCarouselFocusTargetIndex()
            } else {
                gridFocusTargetListIndex.coerceIn(0, getContentLastIndex())
            }
            requestContentFocusOrDefer(targetListIndex = preservedIndex)
        } else {
            requestRootFocusSafe()
        }
    }


    // Padding for the library *list* view (tab bar, grid, search bar) so content
    // never draws behind the display cutout. The window now opts in to
    // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES via Theme.Pluvia, so:
    //   * Status bar visible (portrait):   statusBars insets already cover the top notch.
    //   * Status bar hidden (portrait):    statusBars insets are 0; displayCutout supplies
    //                                       the notch height so content isn't behind the notch.
    //   * Landscape (cutout on a side):    statusBars is top-only; displayCutout supplies
    //                                       the side inset so the tab bar isn't clipped.
    // Bottom is intentionally excluded so scroll content can reach the bottom edge.
    //
    // The detail (game) page deliberately does NOT use this — the hero image is meant
    // to bleed through the cutout, so AppScreenContent insets only the elements that
    // need to stay tappable (e.g. the back button) instead.
    val safePaddingModifier = if (selectedLibraryItem == null) {
        Modifier.windowInsetsPadding(
            WindowInsets.statusBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        )
    } else {
        Modifier
    }

    // Restore focus after tab change - handles both empty and populated tabs
    LaunchedEffect(state.currentTab) {
        if (hasExternalDisplay) {
            gridFocusTargetListIndex = 0
            carouselFocusTargetListIndex = 0
            return@LaunchedEffect
        }
        // Brief delay to let list populate after tab change
        kotlinx.coroutines.delay(150)

        // The user may have moved focus up into the tab bar during the delay; don't yank it back.
        if (tabBarHasFocus) return@LaunchedEffect

        when (libraryFocusTarget(state.appInfoList.size)) {
            LibraryFocusTarget.CONTENT -> {
                // Tab has content - focus the first content item/container.
                requestContentFocusOrDefer(targetListIndex = 0)
            }
            LibraryFocusTarget.ROOT -> {
                // Empty tab - focus root so bumper navigation still works.
                requestRootFocusSafe()
            }
        }
    }

    LaunchedEffect(
        pendingGridFocusRequest,
        gridFocusTargetListIndex,
        state.appInfoList.size,
        selectedAppId,
        isSystemMenuOpen,
        isQuickActionsOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        if (pendingGridFocusRequest && isListFocusable()) {
            if (!hasExternalDisplay && selectedAppId == null && !isSystemMenuOpen && !isQuickActionsOpen && !state.isOptionsPanelOpen && !state.isSearching) {
                var retries = 0
                while (pendingGridFocusRequest && retries < 8) {
                    try {
                        gridFirstItemFocusRequester.requestFocus()
                        pendingGridFocusRequest = false
                    } catch (_: IllegalStateException) {
                        retries++
                        // FocusRequester can be temporarily detached during recomposition.
                        kotlinx.coroutines.delay(32)
                    }
                }
            }
        }
    }

    LaunchedEffect(
        pendingCarouselFocusRequest,
        carouselFocusTargetListIndex,
        state.appInfoList.size,
        selectedAppId,
        isSystemMenuOpen,
        isQuickActionsOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        if (pendingCarouselFocusRequest && isListFocusable()) {
            if (!hasExternalDisplay && selectedAppId == null && !isSystemMenuOpen && !isQuickActionsOpen && !state.isOptionsPanelOpen && !state.isSearching) {
                val targetIndex = currentCarouselFocusTargetIndex()
                if (carouselListState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) {
                    carouselListState.scrollToItem(targetIndex)
                }
                var retries = 0
                while (pendingCarouselFocusRequest && retries < 8) {
                    try {
                        carouselFocusRequester.requestFocus()
                        pendingCarouselFocusRequest = false
                    } catch (_: IllegalStateException) {
                        retries++
                        kotlinx.coroutines.delay(32)
                    }
                }
            }
        }
    }

    // If the app list starts empty and populates later, bootstrap controller focus once content is ready.
    LaunchedEffect(
        state.appInfoList.size,
        selectedAppId,
        isSystemMenuOpen,
        isQuickActionsOpen,
        state.isOptionsPanelOpen,
        state.isSearching,
    ) {
        val currentCount = state.appInfoList.size
        val listBecameNonEmpty = previousAppCount == 0 && currentCount > 0
        val listBecameEmpty = previousAppCount > 0 && currentCount == 0

        if (!hasExternalDisplay && listBecameNonEmpty && selectedAppId == null && !isSystemMenuOpen && !isQuickActionsOpen && !state.isOptionsPanelOpen && !state.isSearching && !tabBarHasFocus) {
            requestContentFocusOrDefer()
        }
        if (!hasExternalDisplay && listBecameEmpty && selectedAppId == null && !isSystemMenuOpen && !isQuickActionsOpen && !state.isOptionsPanelOpen && !state.isSearching && !tabBarHasFocus) {
            // Empty tabs can drop focused children; re-anchor focus at the root so bumper nav keeps working.
            requestRootFocusSafe()
        }

        previousAppCount = currentCount
    }

    // Restore focus when System Menu or Options Panel closes
    LaunchedEffect(isSystemMenuOpen, isQuickActionsOpen, state.isOptionsPanelOpen) {
        val systemMenuJustClosed = wasSystemMenuOpen && !isSystemMenuOpen
        val quickActionsJustClosed = wasQuickActionsOpen && !isQuickActionsOpen
        val optionsPanelJustClosed = wasOptionsPanelOpen && !state.isOptionsPanelOpen

        if (!hasExternalDisplay && (systemMenuJustClosed || quickActionsJustClosed || optionsPanelJustClosed) && !state.isSearching) {
            // Give a brief moment for the overlay to animate out
            kotlinx.coroutines.delay(50)
            // Restore focus to the active content layout
            if (isListFocusable()) {
                requestContentFocusOrDefer()
            } else {
                // Empty list - focus root so bumpers still work
                requestRootFocusSafe()
            }
        }

        // Update previous state trackers
        wasSystemMenuOpen = isSystemMenuOpen
        wasQuickActionsOpen = isQuickActionsOpen
        wasOptionsPanelOpen = state.isOptionsPanelOpen
    }

    // Global key/motion bootstrap path for cases where Compose focus was lost by touch mode.
    // This runs at the app event bus layer, independent of current Compose focus target.
    // Helper functions defined in composable scope to capture latest state on each recomposition.
    val canBootstrapContentFocus: () -> Boolean = {
        val now = SystemClock.uptimeMillis()
            !hasExternalDisplay &&
            selectedAppId == null &&
            !isSystemMenuOpen &&
            !isQuickActionsOpen &&
            !state.isOptionsPanelOpen &&
            !state.isSearching &&
            isListFocusable() &&
            controllerBootstrapNeeded &&
            !rootHasFocus &&
            !tabBarHasFocus &&
            (now - lastBootstrapAtMs) > 250L
    }
    val canNavigateTabsWithoutFocus: () -> Boolean = {
            !hasExternalDisplay &&
            selectedAppId == null &&
            !isSystemMenuOpen &&
            !isQuickActionsOpen &&
            !state.isOptionsPanelOpen &&
            !state.isSearching &&
            !rootHasFocus
    }

    DisposableEffect(Unit) {
        val onGlobalKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (event.action != KeyEvent.ACTION_DOWN) {
                false
            } else {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> {
                        if (canNavigateTabsWithoutFocus()) {
                            onPreviousTab()
                            requestRootFocusSafe()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_BUTTON_R1 -> {
                        if (canNavigateTabsWithoutFocus()) {
                            onNextTab()
                            requestRootFocusSafe()
                            true
                        } else {
                            false
                        }
                    }

                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_BUTTON_L2,
                    KeyEvent.KEYCODE_BUTTON_R2,
                    KeyEvent.KEYCODE_BUTTON_THUMBL,
                    KeyEvent.KEYCODE_BUTTON_THUMBR,
                    -> {
                        if (canBootstrapContentFocus()) {
                            requestContentFocusOrDefer()
                            // Do not consume: let normal key routing continue after bootstrap.
                            false
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }

        val onGlobalMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = { androidEvent ->
            val event = androidEvent.event
            if (event == null || !canBootstrapContentFocus()) {
                false
            } else {
                val isMoveLike = event.actionMasked == MotionEvent.ACTION_MOVE
                val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                val leftX = event.getAxisValue(MotionEvent.AXIS_X)
                val leftY = event.getAxisValue(MotionEvent.AXIS_Y)
                val hasDirectionalAxis = kotlin.math.abs(hatX) >= 0.5f ||
                    kotlin.math.abs(hatY) >= 0.5f ||
                    kotlin.math.abs(leftX) >= 0.6f ||
                    kotlin.math.abs(leftY) >= 0.6f

                if (isMoveLike && hasDirectionalAxis) {
                    requestContentFocusOrDefer()
                    // Do not consume: allow normal movement handling after bootstrap.
                    false
                } else {
                    false
                }
            }
        }

        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)

        onDispose {
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onGlobalKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onGlobalMotionEvent)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(safePaddingModifier)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onFocusChanged { focusState ->
                rootHasFocus = focusState.hasFocus
                if (focusState.hasFocus) {
                    controllerBootstrapNeeded = false
                } else {
                    controllerBootstrapNeeded = true
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                // TODO: consider abstracting this
                // Handle gamepad buttons
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    val canBootstrapContentFocus = !hasExternalDisplay &&
                        selectedAppId == null &&
                        !state.isOptionsPanelOpen &&
                        !isSystemMenuOpen &&
                        !isQuickActionsOpen &&
                        !state.isSearching &&
                        isListFocusable() &&
                        controllerBootstrapNeeded &&
                        // Don't pull focus to the grid while the user is on the tab bar (D-pad
                        // up/left/right and analog nudges aren't consumed by the bar otherwise).
                        !tabBarHasFocus

                    when (keyCode) {
                        // Navigation keys should bootstrap focus even before any item is selected.
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_BUTTON_L2,
                        KeyEvent.KEYCODE_BUTTON_R2,
                        KeyEvent.KEYCODE_BUTTON_THUMBL,
                        KeyEvent.KEYCODE_BUTTON_THUMBR,
                        -> {
                            if (canBootstrapContentFocus) {
                                requestContentFocusOrDefer()
                                false
                            } else {
                                false
                            }
                        }

                        // L1 button - previous tab
                        KeyEvent.KEYCODE_BUTTON_L1 -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen && !isQuickActionsOpen) {
                                if (canBootstrapContentFocus) {
                                    requestContentFocusOrDefer()
                                }
                                onPreviousTab()
                                true
                            } else {
                                false
                            }
                        }

                        // R1 button - next tab
                        KeyEvent.KEYCODE_BUTTON_R1 -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen && !isQuickActionsOpen) {
                                if (canBootstrapContentFocus) {
                                    requestContentFocusOrDefer()
                                }
                                onNextTab()
                                true
                            } else {
                                false
                            }
                        }

                        // SELECT button - toggle options panel (library filters/sort)
                        KeyEvent.KEYCODE_BUTTON_SELECT -> {
                            if (selectedAppId == null && !isSystemMenuOpen && !isQuickActionsOpen) {
                                onOptionsPanelToggle(!state.isOptionsPanelOpen)
                                true
                            } else {
                                false
                            }
                        }

                        // START button - toggle system menu (profile/settings)
                        KeyEvent.KEYCODE_BUTTON_START,
                        KeyEvent.KEYCODE_MENU,
                        -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isQuickActionsOpen) {
                                if (isSystemMenuOpen) layerController?.closeSystem() else layerController?.openSystem()
                                true
                            } else {
                                false
                            }
                        }

                        // Y button - toggle search
                        KeyEvent.KEYCODE_BUTTON_Y -> {
                            if (selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen && !isQuickActionsOpen) {
                                onIsSearching(!state.isSearching)
                                true
                            } else {
                                false
                            }
                        }

                        // X button - add custom game
                        KeyEvent.KEYCODE_BUTTON_X -> {
                            if (selectedAppId == null && !state.isSearching && !state.isOptionsPanelOpen && !isSystemMenuOpen && !isQuickActionsOpen) {
                                onAddCustomGameClick()
                                true
                            } else {
                                false
                            }
                        }

                        // B button - close the active layer or open contextual quick actions.
                        KeyEvent.KEYCODE_BUTTON_B -> {
                            if (selectedAppId != null) {
                                // Let LibraryAppScreen handle its own B-button
                                false
                            } else if (isSystemMenuOpen) {
                                layerController?.closeSystem()
                                true
                            } else if (isQuickActionsOpen) {
                                isQuickActionsOpen = false
                                true
                            } else if (state.isOptionsPanelOpen) {
                                onOptionsPanelToggle(false)
                                true
                            } else if (state.isSearching) {
                                onIsSearching(false)
                                onSearchQuery("")
                                true
                            } else {
                                // Root library view: open contextual quick actions.
                                isQuickActionsOpen = true
                                true
                            }
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        LaunchedEffect(hasExternalDisplay, currentPaneType) {
            if (!hasExternalDisplay && currentPaneType == PaneType.DS_HOME) {
                currentPaneType = PaneType.GRID_CAPSULE
                PrefManager.libraryLayout = PaneType.GRID_CAPSULE
            }
        }

        // In dual-screen mode the upper display is always a passive stage and
        // the lower display owns the complete library workspace. The selected
        // single-screen layout only decides whether that workspace uses covers
        // or an information-dense list.
        if (hasExternalDisplay && selectedAppId == null) {
            val focusedIndex = if (currentPaneType == PaneType.CAROUSEL) {
                currentCarouselFocusTargetIndex()
            } else {
                gridFocusTargetListIndex.coerceIn(0, state.appInfoList.lastIndex.coerceAtLeast(0))
            }
            val focusedItem = state.appInfoList.getOrNull(focusedIndex)
            val gogLoggedIn = app.gamenative.service.gog.GOGAuthManager.hasStoredCredentials(context)
            val epicLoggedIn = app.gamenative.service.epic.EpicAuthManager.hasStoredCredentials(context)
            val amazonLoggedIn = app.gamenative.service.amazon.AmazonAuthManager.hasStoredCredentials(context)
            val systemMenuContent: @Composable () -> Unit = {
                DualSystemMenu(
                    onDismiss = { layerController?.closeSystem() },
                    onNavigateRoute = onNavigateRoute,
                    onDownloadsClick = onDownloadsClick,
                    onLogout = onLogout,
                    onGoOnline = onGoOnline,
                    isOffline = isOffline,
                    gogLoggedIn = gogLoggedIn,
                    epicLoggedIn = epicLoggedIn,
                    amazonLoggedIn = amazonLoggedIn,
                    onGogLoginClick = {
                        gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java))
                    },
                    onGogLogoutClick = {
                        PlatformAuthUiHelpers.logoutGog(
                            context = context,
                            scope = lifecycleScope,
                            callbacks = PlatformLogoutCallbacks(),
                        )
                    },
                    onEpicLoginClick = {
                        epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java))
                    },
                    onEpicLogoutClick = {
                        PlatformAuthUiHelpers.logoutEpic(
                            context = context,
                            scope = lifecycleScope,
                            callbacks = PlatformLogoutCallbacks(),
                        )
                    },
                    onAmazonLoginClick = {
                        amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java))
                    },
                    onAmazonLogoutClick = {
                        PlatformAuthUiHelpers.logoutAmazon(
                            context = context,
                            scope = lifecycleScope,
                            callbacks = PlatformLogoutCallbacks(),
                        )
                    },
                )
            }
            SideEffect {
                DsHomeSecondScreen.publish(
                    if (isSystemMenuOpen) {
                        DsHomeSecondScreen.Model(
                            owner = DsHomeSecondScreen.Owner.LIBRARY,
                            mode = DsHomeSecondScreen.Mode.SETTINGS,
                            controllerNavigation = DsHomeSecondScreen.ControllerNavigation.VERTICAL_LIST,
                            onBack = { layerController?.closeSystem() },
                            settingsContent = systemMenuContent,
                        )
                    } else {
                        DsHomeSecondScreen.Model(
                            owner = DsHomeSecondScreen.Owner.LIBRARY,
                            mode = DsHomeSecondScreen.Mode.GRID,
                            items = state.appInfoList,
                            focusedIndex = focusedIndex,
                            focusedItem = focusedItem,
                            focusedStats = focusedItem?.let { state.statsFor(it) },
                            focusedCompat = focusedItem?.let { state.compatibilityMap[it.name] },
                            installProgressByAppId = state.installProgress,
                            libraryLayout = currentPaneType,
                            currentTab = state.currentTab,
                            isLoading = state.isLoading,
                            isSearching = state.isSearching,
                            searchQuery = state.searchQuery,
                            totalItemCount = state.totalAppsInFilter,
                            currentPage = state.currentPaginationPage,
                            lastPage = state.lastPaginationPage,
                            tabCounts = state.libraryTabCounts(),
                            onSearchQuery = onSearchQuery,
                            onSearchToggle = {
                                if (state.isSearching) {
                                    onIsSearching(false)
                                    onSearchQuery("")
                                } else {
                                    onIsSearching(true)
                                }
                            },
                            onPreviousTab = onPreviousTab,
                            onNextTab = onNextTab,
                            onOptions = { onOptionsPanelToggle(true) },
                            onSystemMenu = { layerController?.openSystem() },
                            onOpenSettings = { onNavigateRoute(PluviaScreen.Settings.route) },
                            onAddGame = onAddCustomGameClick,
                            onQuickActions = { isQuickActionsOpen = true },
                            onLayoutCycle = {
                                val newLayout = if (
                                    currentPaneType == PaneType.LIST ||
                                    currentPaneType == PaneType.INSTALLED_COMPACT
                                ) {
                                    PaneType.GRID_CAPSULE
                                } else {
                                    PaneType.LIST
                                }
                                currentPaneType = newLayout
                                PrefManager.libraryLayout = newLayout
                            },
                            onRefresh = onRefresh,
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                selectedAppId = appId
                                selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                            },
                            onFocused = { idx ->
                                if (currentPaneType == PaneType.CAROUSEL) {
                                    carouselFocusTargetListIndex = idx
                                } else {
                                    gridFocusTargetListIndex = idx
                                }
                            },
                        )
                    },
                )
            }
        }

        DisposableEffect(hasExternalDisplay) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.LIBRARY) }
        }

        if (selectedAppId == null) {
            // Use Box to allow content to scroll behind the tab bar
            Box(modifier = Modifier.fillMaxSize()) {
                val showEmptyStateSplash = when (state.currentTab) {
                    LibraryTab.STEAM -> !SteamUtils.hasStoredCredentials() && !state.isLoading
                    LibraryTab.GOG -> !GOGService.hasStoredCredentials(context)
                    LibraryTab.EPIC -> !EpicService.hasStoredCredentials(context)
                    LibraryTab.AMAZON -> !AmazonService.hasStoredCredentials(context)
                    LibraryTab.LOCAL -> PrefManager.customGamesCount == 0
                    else -> false
                }
                if (showEmptyStateSplash) {
                    val (messageResId, buttonResId, onAction) = when (state.currentTab) {
                        LibraryTab.STEAM -> Triple(
                            R.string.library_source_not_logged_in_steam,
                            R.string.steam_sign_in,
                            onGoOnline,
                        )
                        LibraryTab.GOG -> Triple(
                            R.string.library_source_not_logged_in_gog,
                            R.string.gog_settings_login_title,
                            { gogOAuthLauncher.launch(Intent(context, GOGOAuthActivity::class.java)) },
                        )
                        LibraryTab.EPIC -> Triple(
                            R.string.library_source_not_logged_in_epic,
                            R.string.epic_settings_login_title,
                            { epicOAuthLauncher.launch(Intent(context, EpicOAuthActivity::class.java)) },
                        )
                        LibraryTab.AMAZON -> Triple(
                            R.string.library_source_not_logged_in_amazon,
                            R.string.amazon_settings_login_title,
                            { amazonOAuthLauncher.launch(Intent(context, AmazonOAuthActivity::class.java)) },
                        )
                        LibraryTab.LOCAL -> Triple(
                            R.string.library_source_no_custom_games,
                            R.string.add_custom_game_dialog_title,
                            onAddCustomGameClick,
                        )
                        else -> throw IllegalStateException("showEmptyStateSplash is true only for Steam/GOG/Epic/Amazon/LOCAL")
                    }
                    LibrarySourceNotLoggedInSplash(
                        messageResId = messageResId,
                        signInButtonLabelResId = buttonResId,
                        onSignInClick = onAction,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.loadError) {
                    LibraryStateSplash(
                        messageResId = R.string.library_load_error,
                        actionResId = R.string.connection_retry,
                        onAction = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (isOffline && state.currentTab == LibraryTab.STEAM && state.appInfoList.isEmpty()) {
                    LibraryStateSplash(
                        messageResId = R.string.library_offline_no_games,
                        actionResId = R.string.go_online,
                        onAction = onGoOnline,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!state.isLoading && state.appInfoList.isEmpty()) {
                    LibraryStateSplash(
                        messageResId = if (state.isSearching) {
                            R.string.library_search_no_results
                        } else {
                            R.string.library_no_results
                        },
                        actionResId = if (state.isSearching) {
                            R.string.library_clear_search
                        } else {
                            R.string.connection_retry
                        },
                        onAction = if (state.isSearching) {
                            { onSearchQuery("") }
                        } else {
                            onRefresh
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (hasExternalDisplay) {
                    if (isSystemMenuOpen) {
                        DualScreenAmbientStage(
                            icon = Icons.Default.AccountCircle,
                            label = stringResource(R.string.app_name),
                            title = stringResource(R.string.system_hub_title),
                            description = stringResource(R.string.system_hub_stage_description),
                            accent = PluviaTheme.colors.accentCyan,
                            ambientArtUrls = remember(state.appInfoList) {
                                state.appInfoList.asSequence()
                                    .map { it.heroImageUrl.ifEmpty { it.headerImageUrl } }
                                    .filter { it.isNotEmpty() }
                                    .take(6)
                                    .toList()
                            },
                        )
                    } else {
                        val focusedIndex = if (currentPaneType == PaneType.CAROUSEL) {
                            currentCarouselFocusTargetIndex()
                        } else {
                            gridFocusTargetListIndex.coerceIn(0, state.appInfoList.lastIndex.coerceAtLeast(0))
                        }
                        DsHeroCard(
                            item = state.appInfoList.getOrNull(focusedIndex),
                            onClick = {},
                            interactive = false,
                            installProgress = state.appInfoList.getOrNull(focusedIndex)
                                ?.let { state.installProgress[it.appId] },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    // Library list (content scrolls behind tab bar)
                    if (currentPaneType == PaneType.CAROUSEL) {
                        LibraryCarouselPane(
                            state = state,
                            listState = carouselListState,
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                selectedAppId = appId
                                selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                            },
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                            firstCarouselItemFocusRequester = carouselFocusRequester,
                            focusTargetListIndex = currentCarouselFocusTargetIndex(),
                            onFocusedIndexChanged = { carouselFocusTargetListIndex = it },
                        )
                    } else if (currentPaneType == PaneType.DS_HOME) {
                        LibraryDsHomePane(
                            state = state,
                            listState = listState,
                            firstItemFocusRequester = gridFirstItemFocusRequester,
                            focusTargetListIndex = gridFocusTargetListIndex,
                            onFocusedIndexChanged = { gridFocusTargetListIndex = it },
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                selectedAppId = appId
                                selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                            },
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (currentPaneType == PaneType.INSTALLED_COMPACT) {
                        LibraryCompactRowPane(
                            state = state,
                            isInstalledTab = state.currentTab == LibraryTab.INSTALLED,
                            firstItemFocusRequester = gridFirstItemFocusRequester,
                            focusTargetListIndex = gridFocusTargetListIndex,
                            onFocusedIndexChanged = { gridFocusTargetListIndex = it },
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                selectedAppId = appId
                                selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LibraryListPane(
                            state = state,
                            listState = listState,
                            currentLayout = currentPaneType,
                            firstGridItemFocusRequester = gridFirstItemFocusRequester,
                            focusTargetListIndex = gridFocusTargetListIndex,
                            onFocusedIndexChanged = { gridFocusTargetListIndex = it },
                            onPageChange = onPageChange,
                            onNavigate = { appId ->
                                selectedAppId = appId
                                selectedLibraryItem = state.appInfoList.find { it.appId == appId }
                            },
                            onRefresh = onRefresh,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Top overlay: Tab bar OR Search bar
                if (!hasExternalDisplay && state.isSearching) {
                    // Search overlay replaces tab bar when searching
                    // TODO: Gamepad focus is a bit wonky whenever we show the search bar
                    LibrarySearchBar(
                        isVisible = true,
                        searchQuery = state.searchQuery,
                        resultCount = state.totalAppsInFilter,
                        onScrollToTop = {
                            if (currentPaneType == PaneType.CAROUSEL) {
                                carouselFocusTargetListIndex = 0
                                carouselListState.scrollToItem(0)
                            } else {
                                listState.scrollToItem(0)
                            }
                        },
                        onSearchQuery = onSearchQuery,
                        onDismiss = { onIsSearching(false) },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth(),
                    )
                } else if (!hasExternalDisplay) {
                    // Tab bar when not searching
                    LibraryTabBar(
                        currentTab = state.currentTab,
                        tabs = visibleTabs,
                        currentView = currentPaneType,
                        onViewChanged = { newPaneType ->
                            PrefManager.libraryLayout = newPaneType
                            currentPaneType = newPaneType
                        },
                        tabCounts = state.libraryTabCounts(),
                        onTabSelected = onTabChanged,
                        onOptionsClick = { onOptionsPanelToggle(true) },
                        onSearchClick = { onIsSearching(true) },
                        onRefresh = onRefresh,
                        isRefreshing = state.isRefreshing,
                        onAddGameClick = onAddCustomGameClick,
                        onMenuClick = { layerController?.openSystem() },
                        onNavigateDownToGrid = {
                            if (isListFocusable()) {
                                requestContentFocusOrDefer()
                            }
                        },
                        onPreviousTab = onPreviousTab,
                        onNextTab = onNextTab,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                tabBarHasFocus = focusState.hasFocus
                                // Cancel any deferred grid-focus so the retry loop can't pull focus
                                // back off a tab-bar button the user just landed on.
                                if (focusState.hasFocus) {
                                    pendingGridFocusRequest = false
                                    pendingCarouselFocusRequest = false
                                }
                            },
                    )
                }
            }
        } else {
            LibraryDetailPane(
                libraryItem = selectedLibraryItem,
                onBack = {
                    selectedAppId = null
                    selectedLibraryItem = null
                },
                onClickPlay = {
                    selectedLibraryItem?.let { libraryItem ->
                        onClickPlay(libraryItem.appId, it)
                    }
                },
                onTestGraphics = {
                    selectedLibraryItem?.let { libraryItem ->
                        onTestGraphics(libraryItem.appId)
                    }
                },
                onPlayWithDiagnostics = {
                    selectedLibraryItem?.let { libraryItem ->
                        onPlayWithDiagnostics(libraryItem.appId)
                    }
                },
                onSourceClick = { targetSource ->
                    selectedLibraryItem?.let { current ->
                        resolveCrossStoreSibling(current.appId, targetSource)?.let { target ->
                            selectedAppId = target.appId
                            selectedLibraryItem = target
                        }
                    }
                },
                runPrimaryActionOnOpen = pendingQuickPrimaryAppId == selectedLibraryItem?.appId,
                onPrimaryActionConsumed = { pendingQuickPrimaryAppId = null },
            )
        }

        // Bottom action bar
        if (!hasExternalDisplay && selectedAppId == null && !state.isOptionsPanelOpen && !isSystemMenuOpen && !isQuickActionsOpen) {
            val libraryActions = if (state.isSearching) {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.back,
                        onClick = {
                            onIsSearching(false)
                            onSearchQuery("")
                        },
                    ),
                )
            } else {
                listOf(
                    LibraryActions.select,
                    GamepadAction(
                        button = GamepadButton.SELECT,
                        labelResId = R.string.options,
                        onClick = { onOptionsPanelToggle(true) },
                    ),
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.action_system,
                        onClick = { layerController?.openSystem() },
                    ),
                    GamepadAction(
                        button = GamepadButton.B,
                        labelResId = R.string.menu,
                        onClick = { isQuickActionsOpen = true },
                    ),
                    if (currentPaneType == PaneType.DS_HOME) {
                        GamepadAction(
                            button = GamepadButton.Y,
                            labelResId = R.string.ds_home_icon_size_action,
                        )
                    } else {
                        GamepadAction(
                            button = GamepadButton.Y,
                            labelResId = R.string.search,
                            onClick = { onIsSearching(true) },
                        )
                    },
                ) + listOf(
                    GamepadAction(
                        button = GamepadButton.X,
                        labelResId = R.string.action_add_game,
                        onClick = onAddCustomGameClick,
                    ),
                )
            }

            GamepadActionBar(
                actions = libraryActions,
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = true,
            )
        }

        // Options panel (SELECT) - renders on top of everything
        if (selectedAppId == null) {
            LibraryOptionsPanel(
                isOpen = state.isOptionsPanelOpen,
                onDismiss = { onOptionsPanelToggle(false) },
                selectedFilters = state.appInfoSortType,
                onFilterChanged = onFilterChanged,
                currentSortOption = state.currentSortOption,
                onSortOptionChanged = onSortOptionChanged,
                steamCollections = state.steamCollections,
                selectedSteamCollectionIds = state.selectedSteamCollectionIds,
                steamCollectionCounts = state.steamCollectionCounts,
                skippedDynamicCollections = state.skippedDynamicCollections,
                isSteamConnected = isSteamConnected,
                isOffline = isOffline,
                onSteamCollectionToggle = onSteamCollectionToggle,
                onClearSteamCollections = onClearSteamCollections,
            )

            LibraryQuickActionsPanel(
                isOpen = isQuickActionsOpen,
                focusedItem = focusedLibraryItem,
                onDismiss = { isQuickActionsOpen = false },
                onPrimaryAction = { item ->
                    isQuickActionsOpen = false
                    if (item.isInstalled || item.gameSource == GameSource.CUSTOM_GAME) {
                        onClickPlay(item.appId, false)
                    } else {
                        pendingQuickPrimaryAppId = item.appId
                        selectedAppId = item.appId
                        selectedLibraryItem = item
                    }
                },
                onDetails = { item ->
                    isQuickActionsOpen = false
                    selectedAppId = item.appId
                    selectedLibraryItem = item
                },
                onContainerSettings = { item ->
                    isQuickActionsOpen = false
                    lifecycleScope.launch {
                        val data = withContext(Dispatchers.IO) {
                            ContainerUtils.toContainerData(
                                ContainerUtils.getOrCreateContainer(context, item.appId),
                            )
                        }
                        quickContainerEdit = item to data
                    }
                },
                onAchievements = { item ->
                    isQuickActionsOpen = false
                    quickAchievementsLoading = item
                    lifecycleScope.launch {
                        val list = withContext(Dispatchers.IO) {
                            runCatching { SteamService.fetchAchievementsForDisplay(item.gameId) }.getOrNull()
                        }
                        val rarity = withContext(Dispatchers.IO) {
                            runCatching { app.gamenative.utils.SteamAchievementRarity.fetch(item.gameId) }.getOrNull()
                        }
                        quickAchievementsLoading = null
                        if (list.isNullOrEmpty()) {
                            SnackbarManager.show(context.getString(R.string.quick_action_no_achievements))
                        } else {
                            quickAchievements = Triple(item, list, rarity.orEmpty())
                        }
                    }
                },
                onAddToHome = { item ->
                    isQuickActionsOpen = false
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            app.gamenative.utils.createPinnedShortcut(
                                context = context,
                                gameId = item.gameId,
                                label = item.name,
                                gameSource = item.gameSource,
                                iconUrl = item.capsuleImageUrl.ifEmpty { item.iconHash },
                            )
                            SnackbarManager.show(context.getString(R.string.base_app_shortcut_created))
                        } catch (e: Exception) {
                            SnackbarManager.show(
                                context.getString(R.string.base_app_shortcut_failed, e.message ?: ""),
                            )
                        }
                    }
                },
                onLibraryOptions = {
                    isQuickActionsOpen = false
                    onOptionsPanelToggle(true)
                },
                onSearch = {
                    isQuickActionsOpen = false
                    onIsSearching(true)
                },
                onAddGame = {
                    isQuickActionsOpen = false
                    onAddCustomGameClick()
                },
            )
        }

        // Quick-action overlays: per-game container settings
        quickContainerEdit?.let { (item, containerData) ->
            ContainerConfigDialog(
                title = context.getString(R.string.library_config_title, item.name),
                initialConfig = containerData,
                onDismissRequest = { quickContainerEdit = null },
                onSave = { newData ->
                    ContainerUtils.applyToContainer(context, item.appId, newData)
                    quickContainerEdit = null
                },
            )
        }

        // Quick-action overlays: achievements browser
        quickAchievements?.let { (item, achievements, rarity) ->
            val achievementsContent: @Composable () -> Unit = {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.ButtonB || event.key == Key.Escape)
                            ) {
                                quickAchievements = null
                                true
                            } else {
                                false
                            }
                        },
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SteamAchievementsPage(
                        gameName = item.name,
                        achievements = achievements,
                        onBack = { quickAchievements = null },
                        rarity = rarity,
                    )
                }
            }
            if (hasExternalDisplay) {
                SideEffect {
                    DsHomeSecondScreen.publish(
                        DsHomeSecondScreen.Model(
                            owner = DsHomeSecondScreen.Owner.DIALOG,
                            mode = DsHomeSecondScreen.Mode.SETTINGS,
                            onBack = { quickAchievements = null },
                            settingsContent = achievementsContent,
                        ),
                    )
                }
                DisposableEffect(item.appId) {
                    onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
                }
            } else {
                achievementsContent()
            }
        }

        if (quickAchievementsLoading != null) {
            LoadingDialog(
                visible = true,
                progress = -1f,
                message = stringResource(R.string.main_loading),
            )
        }

        // Pre-import dialog (modern add path)
        if (showModernImportDialog) {
            AlertDialog(
                onDismissRequest = { showModernImportDialog = false },
                title = { Text(stringResource(R.string.add_custom_game_dialog_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.custom_game_import_dialog_message))
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { importRemoveOriginal = !importRemoveOriginal },
                        ) {
                            Checkbox(
                                checked = importRemoveOriginal,
                                onCheckedChange = { importRemoveOriginal = it },
                            )
                            Text(stringResource(R.string.custom_game_import_remove_original))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showModernImportDialog = false
                            importLauncher.launch(null)
                        },
                    ) {
                        Text(stringResource(R.string.continue_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showModernImportDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // Import progress dialog (modern add path)
        if (importState.isImporting) {
            AlertDialog(
                onDismissRequest = { },
                title = { Text(stringResource(R.string.custom_game_importing)) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                Formatter.formatFileSize(
                                    context,
                                    importState.progress?.copiedBytes ?: 0L,
                                ),
                            )
                            importState.progress?.currentFile?.let {
                                Text(text = it, maxLines = 1)
                            }
                        }
                    }
                },
                confirmButton = { },
            )
        }

        ConsoleImportPanel(
            isOpen = showAddCustomGameDialog,
            onDismiss = { showAddCustomGameDialog = false },
            onInstall = {
                showAddCustomGameDialog = false
                installerPicker.launch(arrayOf("*/*"))
            },
            onImportExecutable = {
                showAddCustomGameDialog = false
                portableExecutablePicker.launch(arrayOf("*/*"))
            },
            onChooseFolder = {
                showAddCustomGameDialog = false
                if (BuildConfig.MODERN_ANDROID) {
                    showModernImportDialog = true
                } else {
                    folderPicker.launchPicker()
                }
            },
        )

        // On a dual-screen device this composable is the upper display. Keep the
        // passive system chrome in its conventional top-right corner there; the
        // controller hints remain on the lower display. Single-display library
        // keeps status beside its bottom controller chrome.
        ConsoleStatusIndicators(
            modifier = if (hasExternalDisplay) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 20.dp)
            } else {
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, bottom = 12.dp)
            },
        )
    }
}

/***********
 * PREVIEW *
 ***********/

@OptIn(ExperimentalMaterial3Api::class)
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1080px,height=1920px,dpi=440,orientation=landscape",
)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "id:pixel_tablet",
)
@Composable
private fun Preview_LibraryScreenContent() {
    val sheetState = rememberModalBottomSheetState()
    val context = LocalContext.current
    PrefManager.init(context)
    var state by remember {
        mutableStateOf(
            LibraryState(
                appInfoList = List(15) { idx ->
                    val item = fakeAppInfo(idx)
                    LibraryItem(
                        index = idx,
                        appId = "${GameSource.STEAM.name}_${item.id}",
                        name = item.name,
                        iconHash = item.iconHash,
                    )
                },
                // Add compatibility map for preview
                compatibilityMap = mapOf(
                    "Game 0" to GameCompatibilityStatus.COMPATIBLE,
                    "Game 1" to GameCompatibilityStatus.GPU_COMPATIBLE,
                    "Game 2" to GameCompatibilityStatus.NOT_COMPATIBLE,
                    "Game 3" to GameCompatibilityStatus.UNKNOWN,
                ),
            ),
        )
    }
    PluviaTheme {
        LibraryScreenContent(
            listState = rememberLazyGridState(),
            state = state,
            sheetState = sheetState,
            onIsSearching = {},
            onSearchQuery = {},
            onFilterChanged = { },
            onPageChange = { },
            onModalBottomSheet = {
                val currentState = state.modalBottomSheet
                println("State: $currentState")
                state = state.copy(modalBottomSheet = !currentState)
            },
            onClickPlay = { _, _ -> },
            onTestGraphics = { },
            onPlayWithDiagnostics = { },
            onRefresh = { },
            onNavigateRoute = {},
            onLogout = {},
            onGoOnline = {},
            onSourceToggle = {},
            onAddCustomGameFolder = {},
            onImportPortableExecutable = { _, _ -> },
            onImportInstaller = { _, _ -> },
            onMarkInstallerLaunching = { _, _, _ -> },
            resolveCrossStoreSibling = { _, _ -> null },
            onSortOptionChanged = {},
            onSteamCollectionToggle = {},
            onClearSteamCollections = {},
            onOptionsPanelToggle = { isOpen ->
                state = state.copy(isOptionsPanelOpen = isOpen)
            },
            onTabChanged = { tab ->
                state = state.copy(currentTab = tab)
            },
            onPreviousTab = {},
            onNextTab = {},
        )
    }
}
