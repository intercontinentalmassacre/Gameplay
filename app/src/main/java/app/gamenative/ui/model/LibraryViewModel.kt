package app.gamenative.ui.model

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.LibraryPlayHistory
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCollection
import app.gamenative.data.SteamCollectionRepository
import app.gamenative.events.AndroidEvent
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.data.AppInfo
import app.gamenative.db.dao.LibraryPlayHistoryDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonArtwork
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.steam.SteamCollectionFilter
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import app.gamenative.ui.enums.SortOption
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameImporter
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.ContainerUtils
import app.gamenative.localgames.LocalGameImporter
import app.gamenative.localgames.InstallationSessionStore
import app.gamenative.localgames.InstallationState
import app.gamenative.localgames.InstalledExecutableScanner
import app.gamenative.localgames.LocalInstallerImporter
import app.gamenative.utils.DeviceGameStatsCache
import app.gamenative.utils.GpuGameStatsCache
import app.gamenative.utils.GameCompatibilityCache
import app.gamenative.utils.GameCompatibilityService
import app.gamenative.utils.HardwareUtils
import app.gamenative.utils.normalizeForComparison
import app.gamenative.utils.unaccent
import com.winlator.core.GPUInformation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private const val PLAYABLE_FPS_THRESHOLD = 30
private const val PROVEN_RUNS_THRESHOLD = 5
private const val INITIAL_LOAD_GRACE_MS = 3_000L
private const val CUSTOM_SCAN_CACHE_TTL_MS = 5_000L

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryPlayHistoryDao: LibraryPlayHistoryDao,
    private val steamAppDao: SteamAppDao,
    private val gogGameDao: GOGGameDao,
    private val epicGameDao: EpicGameDao,
    private val amazonGameDao: AmazonGameDao,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryState(isLoading = true))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Keep the library scroll state. This will last longer as the VM will stay alive.
    var listState: LazyGridState by mutableStateOf(LazyGridState(0, 0))

    private val onInstallStatusChanged: (AndroidEvent.LibraryInstallStatusChanged) -> Unit = {
        invalidateLibrarySnapshot()
        onFilterApps(paginationCurrentPage)
    }

    private val onCustomGameImagesFetched: (AndroidEvent.CustomGameImagesFetched) -> Unit = {
        customScanCacheTimeMs = 0L
        // Increment refresh counter and refresh the library list to pick up newly fetched images
        _state.update { it.copy(imageRefreshCounter = it.imageRefreshCounter + 1) }
        onFilterApps(paginationCurrentPage)
    }

    // How many items loaded on one page of results
    @Volatile private var paginationCurrentPage: Int = 0
    @Volatile private var lastPageInCurrentFilter: Int = 0

    // Depot size calculation is expensive and stable until Steam metadata changes.
    private val steamSizeCache = ConcurrentHashMap<String, Long>()

    // First-load grace: suppress the empty-state flash while store DAOs settle
    private val initialLoadStartMs = System.currentTimeMillis()

    // Short-TTL cache for the expensive custom-games disk scan (each filter
    // pass used to rescan the whole storage, which took seconds)
    private var customScanCache: List<LibraryItem> = emptyList()
    private var customScanCacheTimeMs = 0L

    // Complete and unfiltered app list
    private var appList: List<SteamApp> = emptyList()
    private var gogGameList: List<GOGGame> = emptyList()
    private var epicGameList: List<EpicGame> = emptyList()
    private var amazonGameList: List<AmazonGame> = emptyList()
    @Volatile private var crossStoreItemsById: Map<String, List<LibraryItem>> = emptyMap()
    private var playHistoryByAppId: Map<String, Long> = emptyMap()

    /** Disk and Steam database data shared by all tabs. */
    private data class LibrarySnapshot(
        val version: Long,
        val downloadDirectorySet: Set<String>,
        val installedAppsById: Map<Int, AppInfo>,
        val licensedDepotMap: Map<Int, Set<Int>>,
    )

    @Volatile private var librarySnapshot: LibrarySnapshot? = null
    private val librarySnapshotVersion = AtomicLong(0)
    private val filterRequestGeneration = AtomicLong(0)
    private val librarySnapshotMutex = Mutex()

    /**
     * Full owned-library build (items + cross-store sibling map). Rebuilt only when a
     * source list reference changes or the snapshot version advances; filter/search/page
     * passes reuse it instead of remapping every store list each call.
     */
    private class OwnedItemsBuild(
        val snapshotVersion: Long,
        val steam: List<SteamApp>,
        val gog: List<GOGGame>,
        val epic: List<EpicGame>,
        val amazon: List<AmazonGame>,
        val custom: List<LibraryItem>,
        val items: List<LibraryItem>,
        val siblings: Map<String, List<LibraryItem>>,
        val crossStore: Map<String, List<LibraryItem>>,
    )

    @Volatile private var ownedItemsCache: OwnedItemsBuild? = null

    @Volatile private var steamCollections: List<SteamCollection>? = null

    // Track if this is the first load to apply minimum load time
    private var isFirstLoad = true

    // Track debounce job for search
    private var searchDebounceJob: Job? = null
    private val SEARCH_DEBOUNCE_MS = 500L // 500ms debounce

    // Cache GPU name to avoid repeated calls
    private val gpuName: String by lazy {
        try {
            val gpu = GPUInformation.getRenderer(context)
            if (gpu.isNullOrEmpty()) {
                Timber.tag("LibraryViewModel").w("GPU name is null or empty")
                "Unknown GPU"
            } else {
                Timber.tag("LibraryViewModel").d("Retrieved GPU name: $gpu")
                gpu
            }
        } catch (e: Exception) {
            Timber.tag("LibraryViewModel").e(e, "Failed to get GPU name")
            "Unknown GPU"
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (gpuName != "Unknown GPU") {
                DeviceGameStatsCache.refreshIfStale(
                    deviceModel = HardwareUtils.getMachineName(),
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
                GpuGameStatsCache.refreshIfStale(
                    gpuName = gpuName,
                    modernBuild = BuildConfig.MODERN_ANDROID,
                )
            } else {
                Timber.tag("LibraryViewModel").w("Skipping device/GPU game stats fetch - GPU name is unknown")
            }
            _state.update {
                it.copy(
                    deviceGameStats = DeviceGameStatsCache.getAll(),
                    gpuGameStats = GpuGameStatsCache.getAll(),
                )
            }
            // Re-run filtering/sorting now that stats are available, if anything depends on them.
            if (usesStats(_state.value)) {
                onFilterApps(paginationCurrentPage)
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch(Dispatchers.IO) {
            // Re-create the underlying DAO Flow whenever the EXPIRED filter is toggled,
            // so apps with Expired or missing licenses are surfaced/hidden accordingly.
            _state
                .map { it.appInfoSortType.contains(AppFilter.EXPIRED) }
                .distinctUntilChanged()
                .flatMapLatest { includeExpired ->
                    steamAppDao.getAllOwnedApps(includeExpired = includeExpired)
                }
                .collect { apps ->
                    Timber.tag("LibraryViewModel").d("Collecting ${apps.size} apps")
                    // Check if the list has actually changed before triggering a re-filter
                    if (appList != apps) {
                        appList = apps
                        steamSizeCache.clear()
                        invalidateLibrarySnapshot()
                        onFilterApps(paginationCurrentPage)
                    }
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            libraryPlayHistoryDao.getAll().collect { entries ->
                val playHistory = entries.associate { it.appId to it.lastPlayed }
                if (playHistoryByAppId != playHistory) {
                    playHistoryByAppId = playHistory
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Collect GOG games
        viewModelScope.launch(Dispatchers.IO) {
            gogGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} GOG games")
                // Check if the list has actually changed before triggering a re-filter
                if (gogGameList != games) {
                    gogGameList = games
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            epicGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Epic games")

                val hasChanges = epicGameList.size != games.size || epicGameList != games
                epicGameList = games

                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            amazonGameDao.getAll().collect { games ->
                Timber.tag("LibraryViewModel").d("Collecting ${games.size} Amazon games")
                val hasChanges = amazonGameList.size != games.size || amazonGameList != games
                amazonGameList = games
                if (hasChanges) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }

        // Load any cached collections immediately, then observe live updates.
        SteamCollectionRepository.loadFromCache()
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.collections.collect { collections ->
                steamCollections = collections
                // Reconcile the persisted selection against freshly-loaded collections.
                val current = _state.value.selectedSteamCollectionIds
                val recon = SteamCollectionFilter.reconcile(current, collections)
                if (recon.removedAny) {
                    PrefManager.librarySteamCollections = recon.cleaned
                }
                _state.update {
                    it.copy(
                        steamCollections = collections,
                        selectedSteamCollectionIds = recon.cleaned,
                    )
                }
                if (recon.removedAny) {
                    SnackbarManager.show(context.getString(R.string.steam_collections_removed))
                }
                onFilterApps(paginationCurrentPage)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            SteamCollectionRepository.skippedDynamic.collect { skipped ->
                _state.update { it.copy(skippedDynamicCollections = skipped) }
            }
        }

        PluviaApp.events.on<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.on<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
    }

    override fun onCleared() {
        searchDebounceJob?.cancel()
        PluviaApp.events.off<AndroidEvent.LibraryInstallStatusChanged, Unit>(onInstallStatusChanged)
        PluviaApp.events.off<AndroidEvent.CustomGameImagesFetched, Unit>(onCustomGameImagesFetched)
        super.onCleared()
    }

    fun onModalBottomSheet(value: Boolean) {
        _state.update { it.copy(modalBottomSheet = value) }
    }

    fun onIsSearching(value: Boolean) {
        _state.update { it.copy(isSearching = value) }
        if (!value) {
            onSearchQuery("")
        }
    }

    fun onSourceToggle(source: GameSource) {
        val current = _state.value
        when (source) {
            GameSource.STEAM -> {
                val newValue = !current.showSteamInLibrary
                PrefManager.showSteamInLibrary = newValue
                _state.update { it.copy(showSteamInLibrary = newValue) }
            }

            GameSource.CUSTOM_GAME -> {
                val newValue = !current.showCustomGamesInLibrary
                PrefManager.showCustomGamesInLibrary = newValue
                _state.update { it.copy(showCustomGamesInLibrary = newValue) }
            }
            GameSource.GOG -> {
                val newValue = !current.showGOGInLibrary
                PrefManager.showGOGInLibrary = newValue
                _state.update { it.copy(showGOGInLibrary = newValue) }
            }
            GameSource.EPIC -> {
                val newValue = !current.showEpicInLibrary
                PrefManager.showEpicInLibrary = newValue
                _state.update { it.copy(showEpicInLibrary = newValue) }
            }
            GameSource.AMAZON -> {
                val newValue = !current.showAmazonInLibrary
                PrefManager.showAmazonInLibrary = newValue
                _state.update { it.copy(showAmazonInLibrary = newValue) }
            }
        }
        onFilterApps(paginationCurrentPage)
    }

    fun onSortOptionChanged(sortOption: SortOption) {
        PrefManager.librarySortOption = sortOption
        _state.update { it.copy(currentSortOption = sortOption) }
        onFilterApps()
    }

    fun onOptionsPanelToggle(isOpen: Boolean) {
        _state.update { it.copy(isOptionsPanelOpen = isOpen) }
    }

    fun onTabChanged(tab: LibraryTab) {
        if (_state.value.currentTab == tab) return
        _state.update { it.copy(currentTab = tab) }
        onFilterApps(0)
    }

    fun onNextTab() {
        _state.update { currentState ->
            val nextTab = currentState.currentTab.next(context)
            Timber.tag("LibraryViewModel").d("Tab next via bumper: ${currentState.currentTab} -> $nextTab")
            currentState.copy(currentTab = nextTab)
        }
        onFilterApps(0)
    }

    fun onPreviousTab() {
        _state.update { currentState ->
            val previousTab = currentState.currentTab.previous(context)
            Timber.tag("LibraryViewModel").d("Tab previous via bumper: ${currentState.currentTab} -> $previousTab")
            currentState.copy(currentTab = previousTab)
        }
        onFilterApps(0)
    }

    fun onSearchQuery(value: String) {
        // Update UI immediately for responsive typing
        _state.update { it.copy(searchQuery = value) }

        // Cancel previous debounce job
        searchDebounceJob?.cancel()

        // Start new debounce job
        searchDebounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            // Only trigger filter after user stops typing
            onFilterApps()
        }
    }

    // TODO: include other sort types
    fun onFilterChanged(value: AppFilter) {
        _state.update { currentState ->
            val updatedFilter = EnumSet.copyOf(currentState.appInfoSortType)

            if (updatedFilter.contains(value)) {
                updatedFilter.remove(value)
            } else {
                updatedFilter.add(value)
            }

            PrefManager.libraryFilter = updatedFilter

            currentState.copy(appInfoSortType = updatedFilter)
        }

        onFilterApps()
    }

    fun onSteamCollectionToggle(id: String) {
        _state.update { currentState ->
            val updated = currentState.selectedSteamCollectionIds.toMutableSet()
            if (!updated.add(id)) updated.remove(id)
            PrefManager.librarySteamCollections = updated
            currentState.copy(selectedSteamCollectionIds = updated)
        }
        onFilterApps()
    }

    fun onClearSteamCollections() {
        _state.update { currentState ->
            PrefManager.librarySteamCollections = emptySet()
            currentState.copy(selectedSteamCollectionIds = emptySet())
        }
        onFilterApps()
    }

    fun onPageChange(pageIncrement: Int) {
        // Amount to change by
        var toPage = max(0, paginationCurrentPage + pageIncrement)
        toPage = min(toPage, lastPageInCurrentFilter)
        onFilterApps(toPage)
    }

    fun onRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            invalidateLibrarySnapshot()

            // Clear compatibility cache on manual refresh to get fresh data
            GameCompatibilityCache.clear()
            DeviceGameStatsCache.clear()
            GpuGameStatsCache.clear()

            try {
                val newApps = SteamService.refreshOwnedGamesFromServer()
                if (newApps > 0) {
                    Timber.tag("LibraryViewModel").i("Queued $newApps newly owned games for PICS sync")
                } else {
                    Timber.tag("LibraryViewModel").d("No newly owned games discovered during refresh")
                }
                if (app.gamenative.service.gog.GOGService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering GOG library refresh")
                    app.gamenative.service.gog.GOGService.triggerLibrarySync(context)
                }
                if (AmazonService.hasStoredCredentials(context)) {
                    Timber.tag("LibraryViewModel").i("Triggering Amazon library refresh")
                    AmazonService.triggerLibrarySync(context)
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Failed to refresh owned games from server")
            } finally {
                onFilterApps(0).join()
                // Fetch compatibility for current page after refresh
                val currentPageGames = _state.value.appInfoList.map { it.name }
                if (currentPageGames.isNotEmpty()) {
                    fetchCompatibilityForPage(currentPageGames)
                }
                if (gpuName != "Unknown GPU") {
                    DeviceGameStatsCache.refreshIfStale(
                        deviceModel = HardwareUtils.getMachineName(),
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                    GpuGameStatsCache.refreshIfStale(
                        gpuName = gpuName,
                        modernBuild = BuildConfig.MODERN_ANDROID,
                    )
                }
                _state.update {
                    it.copy(
                        isRefreshing = false,
                        deviceGameStats = DeviceGameStatsCache.getAll(),
                        gpuGameStats = GpuGameStatsCache.getAll(),
                    )
                }
                if (usesStats(_state.value)) {
                    onFilterApps(paginationCurrentPage)
                }
            }
        }
    }

    data class CustomGameImportState(
        val isImporting: Boolean = false,
        val progress: CustomGameImporter.Progress? = null,
    )

    private val _importState = MutableStateFlow(CustomGameImportState())
    val importState: StateFlow<CustomGameImportState> = _importState.asStateFlow()

    // Runs in viewModelScope so the copy survives configuration changes; a scope tied to the
    // composition would abort a "remove original" import partway through the move
    fun importCustomGame(uri: Uri, removeOriginal: Boolean) {
        if (_importState.value.isImporting) return
        _importState.value = CustomGameImportState(isImporting = true)
        viewModelScope.launch(Dispatchers.IO) {
            var lastShown = 0L
            val result = CustomGameImporter.importFromTreeUri(context, uri, removeOriginal) { progress ->
                if (progress.copiedBytes - lastShown > 8_000_000L) {
                    lastShown = progress.copiedBytes
                    _importState.value = CustomGameImportState(isImporting = true, progress = progress)
                }
            }
            _importState.value = CustomGameImportState()
            result.onSuccess { path ->
                addCustomGameFolder(path)
                SnackbarManager.show(context.getString(R.string.custom_game_import_success))
            }.onFailure {
                SnackbarManager.show(context.getString(R.string.custom_game_import_failed))
            }
        }
    }

    fun addCustomGameFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedPath = File(path).absolutePath
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(normalizedPath)
            if (libraryItem == null) {
                Timber.tag("LibraryViewModel").w("Selected folder is not a valid custom game: $normalizedPath")
                return@launch
            }

            val manualFolders = PrefManager.customGameManualFolders.toMutableSet()
            if (!manualFolders.contains(normalizedPath)) {
                manualFolders.add(normalizedPath)
                PrefManager.customGameManualFolders = manualFolders
            }

            CustomGameScanner.invalidateCache()
            onFilterApps(paginationCurrentPage)
        }
    }

    /** Imports a selected portable executable through SAF and refreshes the Custom Games tab. */
    fun importPortableExecutable(
        uri: Uri,
        onResult: (LocalGameImporter.ImportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = LocalGameImporter.importPortableExecutable(context, uri)
            if (result is LocalGameImporter.ImportResult.Ready) {
                onFilterApps(paginationCurrentPage)
            }
            onResult(result)
        }
    }

    fun importInstaller(
        uri: Uri,
        onResult: (LocalInstallerImporter.ImportResult) -> Unit,
    ) {
        viewModelScope.launch {
            val result = LocalInstallerImporter.importInstaller(context, uri)
            if (result is LocalInstallerImporter.ImportResult.Ready ||
                result is LocalInstallerImporter.ImportResult.ReadyPortable
            ) {
                onFilterApps(paginationCurrentPage)
            }
            onResult(result)
        }
    }

    fun markInstallerLaunching(
        sessionId: String,
        onReady: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = InstallationSessionStore(context)
                    val session = requireNotNull(store.load(sessionId)) {
                        "Installation session was not found"
                    }
                    require(session.state == InstallationState.READY_TO_LAUNCH) {
                        "Installation is not ready to launch"
                    }
                    val appId = requireNotNull(session.appId) {
                        "Installation session has no container app id"
                    }
                    val container = ContainerUtils.getContainer(context, appId)
                    val baseline = InstalledExecutableScanner.findCandidates(
                        File(container.rootDir, ".wine/drive_c"),
                    )
                    store.save(
                        session.copy(baselineExecutablePaths = baseline)
                            .transitionTo(InstallationState.INSTALLER_RUNNING),
                    )
                    appId
                }
            }
            result.fold(onSuccess = onReady, onFailure = { error ->
                onFailure(error.message ?: "Could not start the installer")
            })
        }
    }

    /** Whether the current sort or any active filter depends on per-game stats. */
    private fun usesStats(state: LibraryState): Boolean {
        val statSorts = setOf(
            SortOption.FPS_HIGH,
            SortOption.RUNS_HIGH,
            SortOption.REVIEWS_HIGH,
            SortOption.REVIEWS_GPU_HIGH,
        )
        if (state.currentSortOption in statSorts) return true
        return state.appInfoSortType.any {
            it == AppFilter.PLAYABLE || it == AppFilter.FIVE_STAR ||
                it == AppFilter.FIVE_STAR_GPU || it == AppFilter.PROVEN_GPU
        }
    }

    /**
     * Returns true if a game satisfies all active stat filters. Applied per-source (like
     * [GameCompatibilityCache]'s compatible filter) so the per-source tab counts stay accurate.
     * Games with no stats data are hidden whenever a stat filter is active.
     */
    private fun passesStatsFilters(state: LibraryState, source: GameSource, name: String): Boolean {
        val filters = state.appInfoSortType
        val playable = filters.contains(AppFilter.PLAYABLE)
        val fiveStar = filters.contains(AppFilter.FIVE_STAR)
        val fiveStarGpu = filters.contains(AppFilter.FIVE_STAR_GPU)
        val proven = filters.contains(AppFilter.PROVEN_GPU)
        if (!playable && !fiveStar && !fiveStarGpu && !proven) return true

        val stats = state.statsFor(source, name)
        if (playable && (stats?.fps ?: 0) < PLAYABLE_FPS_THRESHOLD) return false
        if (fiveStar && (stats?.reviewsDevice ?: 0) < 1) return false
        if (fiveStarGpu && (stats?.reviewsGpu ?: 0) < 1) return false
        if (proven && (stats?.runsGpu ?: 0) < PROVEN_RUNS_THRESHOLD) return false
        return true
    }

    /** Resolves a sibling from the full owned library, independent of filters and pagination. */
    fun resolveCrossStoreSibling(appId: String, targetSource: GameSource): LibraryItem? =
        crossStoreItemsById[appId]?.firstOrNull { it.gameSource == targetSource }

    private fun invalidateLibrarySnapshot() {
        librarySnapshotVersion.incrementAndGet()
        librarySnapshot = null
    }

    private fun onFilterApps(paginationPage: Int = 0): Job {
        Timber.tag("LibraryViewModel").d("onFilterApps - appList.size: ${appList.size}, isFirstLoad: $isFirstLoad")
        val requestGeneration = filterRequestGeneration.incrementAndGet()
        return viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(loadError = false, isLoading = it.appInfoList.isEmpty()) }
            try {
            val currentState = _state.value
            val currentFilter = AppFilter.getAppType(currentState.appInfoSortType)

            val currentVersion = librarySnapshotVersion.get()
            val snapshot = librarySnapshot?.takeIf { it.version == currentVersion }
                ?: librarySnapshotMutex.withLock {
                    val lockedVersion = librarySnapshotVersion.get()
                    librarySnapshot?.takeIf { it.version == lockedVersion } ?: run {
                        val installedApps = SteamService.getAllInstalledApps().orEmpty()
                        LibrarySnapshot(
                            version = lockedVersion,
                            downloadDirectorySet = (
                                DownloadService.getDownloadDirectoryApps() + SteamService.getImportedAppDirs()
                            ).toHashSet(),
                            installedAppsById = installedApps.associateBy { it.id },
                            licensedDepotMap = SteamService.buildLicensedDepotMap(appList),
                        ).also { freshSnapshot ->
                            // Avoid publishing data assembled while the Steam DAO emitted a newer list.
                            if (librarySnapshotVersion.get() == lockedVersion) {
                                librarySnapshot = freshSnapshot
                            }
                        }
                    }
                }
            val downloadDirectorySet = snapshot.downloadDirectorySet

            fun passesCompatibleFilter(gameName: String): Boolean {
                if (!currentState.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                    return true
                }
                val cached = GameCompatibilityCache.getCached(gameName) ?: return true
                val status = compatibilityStatusFor(cached)
                return status == GameCompatibilityStatus.COMPATIBLE || status == GameCompatibilityStatus.GPU_COMPATIBLE
            }

            val nowMs = System.currentTimeMillis()
            val allCustomGameItems = if (nowMs - customScanCacheTimeMs < CUSTOM_SCAN_CACHE_TTL_MS) {
                customScanCache
            } else {
                CustomGameScanner.scanAsLibraryItems().also {
                    customScanCache = it
                    customScanCacheTimeMs = nowMs
                }
            }
            val ownedBuild = ownedItemsCache?.takeIf {
                it.snapshotVersion == currentVersion &&
                    it.steam === appList && it.gog === gogGameList &&
                    it.epic === epicGameList && it.amazon === amazonGameList &&
                    it.custom === allCustomGameItems
            } ?: run {
                val allOwnedItems = buildList {
                appList.forEach { game ->
                    add(
                        LibraryItem(
                            appId = "${GameSource.STEAM.name}_${game.id}",
                            name = game.name,
                            iconHash = game.clientIconHash,
                            capsuleImageUrl = game.getCapsuleUrl(),
                            headerImageUrl = game.headerUrl,
                            heroImageUrl = game.getHeroUrl(),
                            gameSource = GameSource.STEAM,
                            isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(game)),
                        ),
                    )
                }
                gogGameList.forEach { game ->
                    add(
                        LibraryItem(
                            appId = "${GameSource.GOG.name}_${game.id}",
                            name = game.title,
                            iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                            capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                            headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            gameSource = GameSource.GOG,
                            isInstalled = game.isInstalled,
                        ),
                    )
                }
                epicGameList.forEach { game ->
                    add(
                        LibraryItem(
                            appId = "${GameSource.EPIC.name}_${game.id}",
                            name = game.title,
                            iconHash = game.artSquare.ifEmpty { game.artCover },
                            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            gameSource = GameSource.EPIC,
                            isInstalled = game.isInstalled,
                        ),
                    )
                }
                amazonGameList.forEach { game ->
                    val hero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                        .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                    add(
                        LibraryItem(
                            appId = "${GameSource.AMAZON.name}_${game.appId}",
                            name = game.title,
                            iconHash = game.artUrl,
                            capsuleImageUrl = game.artUrl,
                            headerImageUrl = hero,
                            heroImageUrl = hero.ifEmpty { game.artUrl },
                            gameSource = GameSource.AMAZON,
                            isInstalled = game.isInstalled,
                        ),
                    )
                }
                addAll(allCustomGameItems.map { it.copy(isInstalled = true) })
            }
            val siblingGroups = allOwnedItems
                .filter { it.name.normalizeForComparison().isNotEmpty() }
                .groupBy { it.name.normalizeForComparison() }

            fun enrichWithSiblings(item: LibraryItem): LibraryItem {
                val siblings = siblingGroups[item.name.normalizeForComparison()].orEmpty()
                    .filter { it.appId != item.appId && it.gameSource != item.gameSource }
                return item.copy(
                    otherSources = siblings.map { it.gameSource }.distinct(),
                    isInstalledOnOtherSource = siblings.any { it.isInstalled },
                )
            }

            val crossStore = allOwnedItems.associate { item ->
                val siblings = siblingGroups[item.name.normalizeForComparison()].orEmpty()
                    .filter { it.appId != item.appId && it.gameSource != item.gameSource }
                    .map(::enrichWithSiblings)
                item.appId to siblings
            }
                OwnedItemsBuild(
                    snapshotVersion = currentVersion,
                    steam = appList,
                    gog = gogGameList,
                    epic = epicGameList,
                    amazon = amazonGameList,
                    custom = allCustomGameItems,
                    items = allOwnedItems,
                    siblings = siblingGroups,
                    crossStore = crossStore,
                ).also { built ->
                    // Publish only if nothing newer landed while building.
                    if (librarySnapshotVersion.get() == currentVersion) {
                        ownedItemsCache = built
                    }
                }
            }
            crossStoreItemsById = ownedBuild.crossStore

            fun enrichWithSiblings(item: LibraryItem): LibraryItem {
                val siblings = ownedBuild.siblings[item.name.normalizeForComparison()].orEmpty()
                    .filter { it.appId != item.appId && it.gameSource != item.gameSource }
                return item.copy(
                    otherSources = siblings.map { it.gameSource }.distinct(),
                    isInstalledOnOtherSource = siblings.any { it.isInstalled },
                )
            }

            val steamOwnerTypeSearchFiltered: List<SteamApp> = appList
                .asSequence()
                .filter { item ->
                    SteamService.familyMembers.ifEmpty {
                        // Handle the case where userSteamId might be null
                        SteamService.userSteamId?.let { steamId ->
                            listOf(steamId.accountID.toInt())
                        } ?: emptyList()
                    }.let { owners ->
                        if (owners.isEmpty()) {
                            true // no owner info ⇒ don’t filter the item out
                        } else {
                            owners.any { item.ownerAccountId.contains(it) }
                        }
                    }
                }
                .filter { item ->
                    currentFilter.any { item.type == it }
                }
                .filter { item ->
                    if (currentState.appInfoSortType.contains(AppFilter.SHARED)) {
                        true
                    } else {
                        item.ownerAccountId.contains(PrefManager.steamUserAccountId) || PrefManager.steamUserAccountId == 0
                    }
                }
                .filter { item ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(item.name, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .toList()

            val explicitInstalledOnly = currentState.appInfoSortType.contains(AppFilter.INSTALLED)
            val stableSteamOwnerTypeFiltered = if (explicitInstalledOnly) {
                steamOwnerTypeSearchFiltered.filter { item ->
                    downloadDirectorySet.contains(SteamService.getAppDirName(item))
                }
            } else {
                steamOwnerTypeSearchFiltered
            }
            val steamOwnerTypeFiltered = if (currentState.currentTab.installedOnly) {
                stableSteamOwnerTypeFiltered.filter { item ->
                    downloadDirectorySet.contains(SteamService.getAppDirName(item))
                }
            } else {
                stableSteamOwnerTypeFiltered
            }

            // Per-collection counts: computed from the owner/type/search-filtered set (independent of the
            // current collection selection) so each collection shows how many games it would contribute.
            val steamCollectionCounts: Map<String, Int> = steamCollections?.associate { collection ->
                collection.id to stableSteamOwnerTypeFiltered.count { it.id in collection.appIds }
            } ?: emptyMap()

            // Apply the Steam collection filter — union/OR, fail-open (see SteamCollectionFilter).
            // Resolve the allowed app-id set once for the whole pass instead of per app.
            val allowedSteamAppIds = SteamCollectionFilter.allowedAppIds(
                selectedIds = currentState.selectedSteamCollectionIds,
                collections = steamCollections,
            )
            val steamFilteredBeforeCompatibility: List<SteamApp> =
                (
                    if (allowedSteamAppIds == null) {
                        steamOwnerTypeFiltered
                    } else {
                        steamOwnerTypeFiltered.filter { it.id in allowedSteamAppIds }
                    }
                )

            val stableSteamFilteredBeforeCompatibility: List<SteamApp> =
                if (allowedSteamAppIds == null) {
                    stableSteamOwnerTypeFiltered
                } else {
                    stableSteamOwnerTypeFiltered.filter { it.id in allowedSteamAppIds }
                }

            // Filter Steam apps first (no pagination yet)
            // Note: Don't sort individual lists - we'll sort the combined list for consistent ordering
            val filteredSteamApps: List<SteamApp> = steamFilteredBeforeCompatibility
                .asSequence()
                .filter { item -> passesCompatibleFilter(item.name) }
                .filter { item -> passesStatsFilters(currentState, GameSource.STEAM, item.name) }
                .sortedWith(
                    compareByDescending<SteamApp> {
                        downloadDirectorySet.contains(SteamService.getAppDirName(it))
                    }.thenBy { it.name.lowercase() },
                )
                .toList()

            val stableSteamCount = stableSteamFilteredBeforeCompatibility.count { item ->
                passesCompatibleFilter(item.name) &&
                    passesStatsFilters(currentState, GameSource.STEAM, item.name)
            }

            // Map Steam apps to UI items
            data class LibraryEntry(val item: LibraryItem, val isInstalled: Boolean, val lastPlayed: Long = 0L) {
                val sortName: String = item.name.lowercase()
            }

            fun lastPlayedFor(appId: String): Long = playHistoryByAppId[appId] ?: 0L

            val licensedDepotMap = snapshot.licensedDepotMap

            // Added this to avoid duplicate from custom imported steam game
            val steamEntriesAppIds = mutableSetOf<String>()

            val steamEntries: List<LibraryEntry> = filteredSteamApps.map { item ->
                val isInstalled = downloadDirectorySet.contains(SteamService.getAppDirName(item))
                val installedBranch = if (isInstalled) {
                    snapshot.installedAppsById[item.id]?.branch ?: "public"
                } else {
                    "public"
                }
                // base-game size: ownedDlc=emptyMap excludes DLC depots
                val licensedDepots = licensedDepotMap[item.id]
                val totalSizeBytes = steamSizeCache.getOrPut("${item.id}:$installedBranch") {
                    val resolved = SteamService.resolveDownloadableDepots(item.depots, "", emptyMap(), licensedDepots)
                    resolved.values.sumOf { depot ->
                        depot.manifests[installedBranch]?.size ?: depot.manifests.values.firstOrNull()?.size ?: 0L
                    }
                }

                // Move appId here
                val appId = "${GameSource.STEAM.name}_${item.id}"
                steamEntriesAppIds.add(appId)

                LibraryEntry(
                    item = LibraryItem(
                        index = 0, // temporary, will be re-indexed after combining and paginating
                        appId = appId,
                        name = item.name,
                        iconHash = item.clientIconHash,
                        capsuleImageUrl = item.getCapsuleUrl(),
                        headerImageUrl = item.headerUrl,
                        heroImageUrl = item.getHeroUrl(),
                        isShared = (PrefManager.steamUserAccountId != 0 && !item.ownerAccountId.contains(PrefManager.steamUserAccountId)),
                        sizeBytes = totalSizeBytes,
                    ),
                    isInstalled = isInstalled,
                    lastPlayed = lastPlayedFor(appId),
                )
            }

            // Scan Custom Games roots and create UI items (filtered by search query inside scanner)
            // Only include custom games if GAME filter is selected
            val customGameItems = if (currentState.appInfoSortType.contains(AppFilter.GAME)) {
                allCustomGameItems.filter {
                    currentState.searchQuery.isEmpty() || matches(it.name, currentState.searchQuery)
                }
            } else {
                emptyList()
            }
            val customEntries = customGameItems
                .filter { !steamEntriesAppIds.contains(it.appId) } // Filter out imported steam appId
                .filter { passesStatsFilters(currentState, it.gameSource, it.name) }
                .map { LibraryEntry(it, true, lastPlayed = lastPlayedFor(it.appId)) }

            // Filter GOG games
            val filteredGOGGames = gogGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val gogEntries = filteredGOGGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.GOG, it.title) }
                .map { game ->
                    val appId = "${GameSource.GOG.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.iconUrl.ifEmpty { game.imageUrl },
                            capsuleImageUrl = game.verticalCoverUrl.ifEmpty { game.iconUrl.ifEmpty { game.imageUrl } },
                            headerImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            heroImageUrl = game.imageUrl.ifEmpty { game.iconUrl },
                            isShared = false,
                            gameSource = GameSource.GOG,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Filter Epic games
            val filteredEpicGames = epicGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val epicEntries = filteredEpicGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.EPIC, it.title) }
                .map { game ->
                    val appId = "${GameSource.EPIC.name}_${game.id}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artSquare.ifEmpty { game.artCover },
                            capsuleImageUrl = game.artCover.ifEmpty { game.artSquare },
                            headerImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            heroImageUrl = game.artPortrait.ifEmpty { game.artSquare.ifEmpty { game.artCover } },
                            isShared = false,
                            gameSource = GameSource.EPIC,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            // Amazon games
            val filteredAmazonGames = amazonGameList
                .asSequence()
                .filter { game ->
                    if (currentState.searchQuery.isNotEmpty()) {
                        matches(game.title, currentState.searchQuery)
                    } else {
                        true
                    }
                }
                .filter { game ->
                    val installedOnly = currentState.currentTab.installedOnly ||
                        currentState.appInfoSortType.contains(AppFilter.INSTALLED)
                    if (installedOnly) {
                        game.isInstalled
                    } else {
                        true
                    }
                }
                .toList()

            val amazonEntries = filteredAmazonGames
                .filter { passesCompatibleFilter(it.title) }
                .filter { passesStatsFilters(currentState, GameSource.AMAZON, it.title) }
                .map { game ->
                    val layoutHero = AmazonArtwork.layoutHeroFromProductJson(game.productJson)
                        .ifEmpty { game.heroUrl.ifEmpty { game.artUrl } }
                    val appId = "${GameSource.AMAZON.name}_${game.appId}"
                    LibraryEntry(
                        item = LibraryItem(
                            index = 0,
                            appId = appId,
                            name = game.title,
                            iconHash = game.artUrl,
                            capsuleImageUrl = game.artUrl,
                            headerImageUrl = layoutHero,
                            heroImageUrl = layoutHero.ifEmpty { game.artUrl },
                            gridHeroImageScale = AmazonArtwork.GRID_HERO_ZOOM_SCALE,
                            isShared = false,
                            gameSource = GameSource.AMAZON,
                        ),
                        isInstalled = game.isInstalled,
                        lastPlayed = lastPlayedFor(appId),
                    )
                }

            if (filterRequestGeneration.get() != requestGeneration) {
                Timber.tag("LibraryViewModel").d("Discarding stale library filter generation $requestGeneration")
                return@launch
            }

            // Calculate installed counts
            val gogInstalledCount = filteredGOGGames.count { it.isInstalled }
            val epicInstalledCount = filteredEpicGames.count { it.isInstalled }
            val amazonInstalledCount = filteredAmazonGames.count { it.isInstalled }
            // Save game counts for skeleton loaders (only when not searching, to get accurate counts)
            // This needs to happen before filtering by source, so we save the total counts
            if (currentState.searchQuery.isEmpty()) {
                PrefManager.customGamesCount = customGameItems.size
                PrefManager.steamGamesCount = stableSteamFilteredBeforeCompatibility.size
                PrefManager.gogGamesCount = filteredGOGGames.size
                PrefManager.gogInstalledGamesCount = gogInstalledCount
                PrefManager.epicGamesCount = filteredEpicGames.size
                PrefManager.epicInstalledGamesCount = epicInstalledCount
                PrefManager.amazonInstalledGamesCount = amazonInstalledCount
                Timber.tag("LibraryViewModel").d("Saved counts - Custom: ${customGameItems.size}, Steam: ${stableSteamFilteredBeforeCompatibility.size}, GOG: ${filteredGOGGames.size}, GOG installed: $gogInstalledCount, Epic: ${filteredEpicGames.size}, Epic installed: $epicInstalledCount, Amazon installed: $amazonInstalledCount")
            }

            // Compute effective source filters based on current tab
            // ALL tab uses user preferences, other tabs override with their presets
            // Use captured currentState (not _state.value) to avoid TOCTOU race
            val currentTab = currentState.currentTab
            val isInstalledLibrary = currentTab == LibraryTab.INSTALLED
            val hasGOGCredentials = GOGService.hasStoredCredentials(context)
            val hasEpicCredentials = EpicService.hasStoredCredentials(context)
            val hasAmazonCredentials = AmazonService.hasStoredCredentials(context)
            val includeSteam = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showSteamInLibrary
            } else {
                currentTab.showSteam
            }
            val includeOpen = if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showCustomGamesInLibrary
            } else {
                currentTab.showCustom
            }

            val includeGOG = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showGOGInLibrary
            } else {
                currentTab.showGoG
            }) && (isInstalledLibrary || hasGOGCredentials)

            val includeEpic = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showEpicInLibrary
            } else {
                currentTab.showEpic
            }) && (isInstalledLibrary || hasEpicCredentials)

            val includeAmazon = (if (currentTab == app.gamenative.ui.enums.LibraryTab.ALL) {
                currentState.showAmazonInLibrary
            } else {
                currentTab.showAmazon
            }) && (isInstalledLibrary || hasAmazonCredentials)

            // Combine both lists and apply sort option
            val sortComparator: Comparator<LibraryEntry> = when (currentState.currentSortOption) {
                SortOption.INSTALLED_FIRST -> compareBy<LibraryEntry> { entry ->
                    if (entry.isInstalled) 0 else 1
                }.thenBy { it.sortName }

                SortOption.NAME_ASC -> compareBy { it.sortName }

                SortOption.NAME_DESC -> compareByDescending { it.sortName }

                SortOption.RECENTLY_PLAYED -> LibrarySortUtils.recentlyPlayedComparator(
                    name = { it.sortName },
                    isInstalled = { it.isInstalled },
                    lastPlayed = { it.lastPlayed },
                )

                SortOption.SIZE_SMALLEST -> compareBy<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.sortName }

                SortOption.SIZE_LARGEST -> compareByDescending<LibraryEntry> { it.item.sizeBytes }
                    .thenBy { it.sortName }

                SortOption.FPS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.fps ?: -1
                }.thenBy { it.sortName }

                SortOption.RUNS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.runsGpu ?: -1
                }.thenBy { it.sortName }

                SortOption.REVIEWS_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsDevice ?: -1
                }.thenBy { it.sortName }

                SortOption.REVIEWS_GPU_HIGH -> compareByDescending<LibraryEntry> {
                    currentState.statsFor(it.item)?.reviewsGpu ?: -1
                }.thenBy { it.sortName }
            }

            // A Steam collection can only contain Steam apps, so when one is selected the non-Steam
            // sources can't match it — keep them out of the combined list (and their tab counts).
            val steamCollectionSelected = allowedSteamAppIds != null

            val combined = buildList {
                if (includeSteam) addAll(steamEntries)
                if (includeOpen && !steamCollectionSelected) addAll(customEntries)
                if (includeGOG && !steamCollectionSelected) addAll(gogEntries)
                if (includeEpic && !steamCollectionSelected) addAll(epicEntries)
                if (includeAmazon && !steamCollectionSelected) addAll(amazonEntries)
            }.map { entry -> entry.copy(item = enrichWithSiblings(entry.item)) }
                .sortedWith(sortComparator).mapIndexed { idx, entry ->
                entry.item.copy(index = idx, isInstalled = entry.isInstalled)
            }

            // Total count for the current filter
            val totalFound = combined.size

            val installedSteamCount = if (currentState.showSteamInLibrary) {
                stableSteamFilteredBeforeCompatibility.count { item ->
                    downloadDirectorySet.contains(SteamService.getAppDirName(item)) &&
                        passesCompatibleFilter(item.name) &&
                        passesStatsFilters(currentState, GameSource.STEAM, item.name)
                }
            } else {
                0
            }
            val installedCount = installedSteamCount + if (steamCollectionSelected) {
                0
            } else {
                (if (currentState.showCustomGamesInLibrary) customEntries.size else 0) +
                    (if (currentState.showGOGInLibrary) gogEntries.count { it.isInstalled } else 0) +
                    (if (currentState.showEpicInLibrary) epicEntries.count { it.isInstalled } else 0) +
                    (if (currentState.showAmazonInLibrary) amazonEntries.count { it.isInstalled } else 0)
            }

            // Determine how many pages and slice the list for incremental loading
            val pageSize = PrefManager.itemsPerPage
            // Update internal pagination state
            paginationCurrentPage = paginationPage
            lastPageInCurrentFilter = if (totalFound == 0) 0 else (totalFound - 1) / pageSize
            // Calculate how many items to show: (pagesLoaded * pageSize)
            val endIndex = min((paginationPage + 1) * pageSize, totalFound)
            val pagedList = combined.take(endIndex)

            Timber.tag("LibraryViewModel").d("Filtered list size (with Custom Games): $totalFound")

            if (isFirstLoad) {
                isFirstLoad = false
            }

            // Fetch compatibility for current page games
            fetchCompatibilityForPage(pagedList.map { it.name })

            // The first filter pass can complete while the store DAOs have not
            // emitted yet (empty list -> "no results" flash). Hold the loading
            // flag until data arrives or the grace period elapses.
            val completesInitialLoad = pagedList.isNotEmpty() ||
                System.currentTimeMillis() - initialLoadStartMs > INITIAL_LOAD_GRACE_MS

            _state.update {
                it.copy(
                    appInfoList = pagedList,
                    currentPaginationPage = paginationPage + 1, // visual display is not 0 indexed
                    lastPaginationPage = lastPageInCurrentFilter + 1,
                    totalAppsInFilter = totalFound,
                    isLoading = if (it.hasCompletedInitialLoad || completesInitialLoad) false else true,
                    hasCompletedInitialLoad = it.hasCompletedInitialLoad || completesInitialLoad,
                    // Per-source counts for tab badges
                    // Use user prefs + auth state only (not current tab) so badges stay stable across tab switches
                    installedCount = installedCount,
                    allCount = (if (currentState.showSteamInLibrary) stableSteamCount else 0) +
                        (if (currentState.showCustomGamesInLibrary) customEntries.size else 0) +
                        (if (currentState.showGOGInLibrary && GOGService.hasStoredCredentials(context)) gogEntries.size else 0) +
                        (if (currentState.showEpicInLibrary && EpicService.hasStoredCredentials(context)) epicEntries.size else 0) +
                        (if (currentState.showAmazonInLibrary && AmazonService.hasStoredCredentials(context)) amazonEntries.size else 0),
                    steamCount = if (currentState.showSteamInLibrary) stableSteamCount else 0,
                    gogCount = if (currentState.showGOGInLibrary && GOGService.hasStoredCredentials(context)) gogEntries.size else 0,
                    epicCount = if (currentState.showEpicInLibrary && EpicService.hasStoredCredentials(context)) epicEntries.size else 0,
                    amazonCount = if (currentState.showAmazonInLibrary && AmazonService.hasStoredCredentials(context)) amazonEntries.size else 0,
                    localCount = if (currentState.showCustomGamesInLibrary) customEntries.size else 0,
                    steamCollectionCounts = steamCollectionCounts,
                )
            }
            } catch (error: Exception) {
                Timber.tag("LibraryViewModel").e(error, "Failed to filter library")
                if (filterRequestGeneration.get() == requestGeneration) {
                    _state.update { it.copy(isLoading = false, loadError = true) }
                }
            }
        }
    }

    /**
     * Compares the game name against the search query using an exact match
     * and then again using a normalized form with diacritics removed.
     */
    private fun matches(gameName: String, searchQuery:String): Boolean {
        return gameName.contains(searchQuery, ignoreCase = true) || gameName.unaccent().contains(searchQuery, ignoreCase = true)
    }

    /**
     * Fetches compatibility information for games in paginated batches.
     * Checks cache first, then fetches uncached games in batches of 50.
     */
    private fun fetchCompatibilityForPage(gameNames: List<String>) {
        if (gameNames.isEmpty()) {
            Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: No game names provided")
            return
        }

        Timber.tag("LibraryViewModel").d("fetchCompatibilityForPage: Fetching compatibility for ${gameNames.size} games, GPU: $gpuName")

        // Don't make API calls if GPU name is unknown
        if (gpuName == "Unknown GPU") {
            Timber.tag("LibraryViewModel").w("Skipping compatibility fetch - GPU name is unknown")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Separate cached and uncached games
                val uncachedGames = mutableListOf<String>()
                val cachedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (gameName in gameNames) {
                    val cached = GameCompatibilityCache.getCached(gameName)
                    if (cached != null) {
                        cachedResults[gameName] = cached
                        Timber.tag("LibraryViewModel").d("Using cached result for: $gameName")
                    } else {
                        uncachedGames.add(gameName)
                    }
                }

                Timber.tag("LibraryViewModel").d("Cached: ${cachedResults.size}, Uncached: ${uncachedGames.size}")

                // Update state with cached results immediately (for instant UI update)
                if (cachedResults.isNotEmpty()) {
                    updateCompatibilityState(cachedResults)
                }

                // Only fetch if there are uncached games
                if (uncachedGames.isEmpty()) {
                    Timber.tag("LibraryViewModel").d("All games in page are cached, skipping API call")
                    return@launch
                }

                // Fetch uncached games in batches of 25
                val batchSize = 25
                val fetchedResults = mutableMapOf<String, GameCompatibilityService.GameCompatibilityResponse>()

                for (i in uncachedGames.indices step batchSize) {
                    val batch = uncachedGames.subList(i, min(i + batchSize, uncachedGames.size))
                    Timber.tag("LibraryViewModel").d("Fetching batch ${i / batchSize + 1} with ${batch.size} games")
                    val batchResults = GameCompatibilityService.fetchCompatibility(batch, gpuName)

                    if (batchResults != null) {
                        Timber.tag("LibraryViewModel").d("Received ${batchResults.size} results from API")
                        // Cache all results using batch caching
                        GameCompatibilityCache.cacheAll(batchResults)
                        fetchedResults.putAll(batchResults)
                    } else {
                        Timber.tag("LibraryViewModel").w("API returned null for batch")
                    }
                }

                // Update state with newly fetched results
                if (fetchedResults.isNotEmpty()) {
                    updateCompatibilityState(fetchedResults)
                    // Re-apply list filtering once new compatibility data is available
                    if (_state.value.appInfoSortType.contains(AppFilter.COMPATIBLE)) {
                        onFilterApps(paginationCurrentPage)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("LibraryViewModel").e(e, "Error fetching compatibility data: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /**
     * Updates the state with compatibility results.
     */
    private fun updateCompatibilityState(
        results: Map<String, GameCompatibilityService.GameCompatibilityResponse>
    ) {
        val compatibilityMap = results.mapValues { (gameName, response) ->
            compatibilityStatusFor(response)
        }

        // Update state with compatibility map (merge with existing)
        _state.update { currentState ->
            val mergedMap = currentState.compatibilityMap.toMutableMap()
            mergedMap.putAll(compatibilityMap)
            Timber.tag("LibraryViewModel").d("Updated state with ${compatibilityMap.size} compatibility entries, total: ${mergedMap.size}")
            currentState.copy(compatibilityMap = mergedMap)
        }
    }

    private fun compatibilityStatusFor(
        response: GameCompatibilityService.GameCompatibilityResponse,
    ): GameCompatibilityStatus {
        return when {
            response.isNotWorking -> GameCompatibilityStatus.NOT_COMPATIBLE
            !response.hasBeenTried -> GameCompatibilityStatus.UNKNOWN
            response.gpuPlayableCount > 0 -> GameCompatibilityStatus.GPU_COMPATIBLE
            response.totalPlayableCount > 0 -> GameCompatibilityStatus.COMPATIBLE
            else -> GameCompatibilityStatus.UNKNOWN
        }
    }
}
