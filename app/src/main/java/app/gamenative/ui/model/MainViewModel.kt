package app.gamenative.ui.model

import android.content.Context
import android.os.Process
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.CrashContext
import app.gamenative.diagnostics.LaunchStage
import app.gamenative.diagnostics.LaunchTrace
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.GameProcessInfo
import app.gamenative.data.GameSource
import app.gamenative.di.IAppTheme
import app.gamenative.enums.AppTheme
import app.gamenative.enums.LoginResult
import app.gamenative.enums.PathType
import app.gamenative.events.AndroidEvent
import app.gamenative.events.SteamEvent
import app.gamenative.localgames.LocalContainerLaunch
import app.gamenative.localgames.LocalInstallerCompletionCoordinator
import app.gamenative.localgames.InstallationSessionStore
import app.gamenative.localgames.InstallationState
import app.gamenative.ui.enums.Orientation
import java.util.EnumSet
import app.gamenative.service.ActiveGameRegistry
import app.gamenative.service.SteamService
import app.gamenative.service.ExitCloudSyncQueue
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicCloudSavesManager
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.ui.data.MainState
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.screen.PluviaScreen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.AndroidGameLauncher
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.IntentLaunchManager
import app.gamenative.utils.LibrarySessionTracker
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.UpdateInfo
import app.gamenative.utils.WineProcessSnapshotHelper
import com.materialkolor.PaletteStyle
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.xserver.Window
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.dragonbra.javasteam.steam.handlers.steamapps.AppProcessInfo
import java.nio.file.Paths
import javax.inject.Inject
import kotlin.io.path.name
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class MainViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val appTheme: IAppTheme,
    private val librarySessions: LibrarySessionTracker,
    private val itchLibraryRepository: app.gamenative.itch.ItchLibraryRepository,
    private val itchInstallCoordinator: app.gamenative.itch.ItchInstallCoordinator,
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_SCREEN_ROUTE = "current_screen_route"
    }

    sealed class MainUiEvent {
        data object OnBackPressed : MainUiEvent()
        data object OnLoggedOut : MainUiEvent()
        data object LaunchApp : MainUiEvent()
        data class ExternalGameLaunch(val appId: String) : MainUiEvent()
        data class OnLogonEnded(val result: LoginResult) : MainUiEvent()
        data class SteamDisconnected(val isTerminal: Boolean) : MainUiEvent()
        data class ShowGameFeedbackDialog(val appId: String) : MainUiEvent()
        data class SelectInstallerExecutable(
            val sessionId: String,
            val title: String,
            val candidates: List<String>,
        ) : MainUiEvent()
        data class InstallerCompletionFailed(
            val title: String,
            val reason: String,
            val sessionId: String? = null,
        ) : MainUiEvent()
        data class InstallerCompleted(val title: String) : MainUiEvent()
        data class RetryInstallerLaunch(val appId: String) : MainUiEvent()
        data object ServiceReady : MainUiEvent()
    }

    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    private val _uiEvent = Channel<MainUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> get() = _offline

    fun setOffline(value: Boolean) {
        _offline.value = value
    }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    fun setUpdateInfo(info: UpdateInfo?) {
        _updateInfo.value = info
    }

    private val onSteamConnected: (SteamEvent.Connected) -> Unit = {
        Timber.i("Received is connected")
        _state.update {
            it.copy(
                isSteamConnected = true,
                connectionState = ConnectionState.CONNECTED,
            )
        }
    }

    private val onSteamDisconnected: (SteamEvent.Disconnected) -> Unit = { event ->
        Timber.i("Received disconnected from Steam (terminal=${event.isTerminal})")
        _state.update {
            it.copy(
                isSteamConnected = false,
                connectionState = if (it.connectionState != ConnectionState.OFFLINE_MODE) {
                    ConnectionState.DISCONNECTED
                } else {
                    it.connectionState // Keep offline mode if user chose it
                },
                connectionMessage = null,
            )
        }
        viewModelScope.launch { _uiEvent.send(MainUiEvent.SteamDisconnected(event.isTerminal)) }
    }

    private val onRemotelyDisconnected: (SteamEvent.RemotelyDisconnected) -> Unit = {
        Timber.i("Received remotely disconnected from Steam")
        _state.update {
            it.copy(
                isSteamConnected = false,
                connectionState = if (it.connectionState != ConnectionState.OFFLINE_MODE) {
                    ConnectionState.DISCONNECTED
                } else {
                    it.connectionState // Keep offline mode if user chose it
                },
                connectionMessage = null,
            )
        }
        viewModelScope.launch { _uiEvent.send(MainUiEvent.SteamDisconnected(isTerminal = false)) }
    }

    private val onLoggingIn: (SteamEvent.LogonStarted) -> Unit = {
        Timber.i("Received logon started")
        _state.update {
            it.copy(
                connectionMessage = null,
                isSteamConnected = true,
            )
        }
    }

    private val onBackPressed: (AndroidEvent.BackPressed) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnBackPressed)
        }
    }

    private val onLogonEnded: (SteamEvent.LogonEnded) -> Unit = { event ->
        Timber.tag("MainViewModel").i("Received logon ended")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLogonEnded(event.loginResult))
        }
        // Update connection state based on login result
        when (event.loginResult) {
            LoginResult.Success -> {
                _state.update {
                    it.copy(
                        connectionMessage = null,
                        connectionTimeoutSeconds = 0,
                    )
                }
            }

            LoginResult.Failed -> {
                _state.update {
                    it.copy(
                        connectionMessage = event.message, // null falls back to UI string resource
                    )
                }
            }

            else -> {
                // DeviceAuth, DeviceConfirm, EmailAuth, InProgress - keep connecting state
            }
        }
    }

    private val onLoggedOut: (SteamEvent.LoggedOut) -> Unit = {
        Timber.tag("MainViewModel").i("Received logged out")
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.OnLoggedOut)
        }
        // Session expired or user logged out - must re-authenticate
        _state.update {
            it.copy(
                connectionState = ConnectionState.LOGGED_OUT,
                connectionMessage = null,
                isSteamConnected = false,
            )
        }
    }

    private val onExternalGameLaunch: (AndroidEvent.ExternalGameLaunch) -> Unit = {
        Timber.tag("MainViewModel").i("Received external game launch event for app ${it.appId}")
        viewModelScope.launch {
            Timber.tag("MainViewModel").i("Sending ExternalGameLaunch UI event for app ${it.appId}")
            _uiEvent.send(MainUiEvent.ExternalGameLaunch(it.appId))
        }
    }

    private val onServiceReady: (AndroidEvent.ServiceReady) -> Unit = {
        viewModelScope.launch {
            _uiEvent.send(MainUiEvent.ServiceReady)
        }
    }

    private val onSetBootingSplashText: (AndroidEvent.SetBootingSplashText) -> Unit = {
        setBootingSplashText(it.text)
    }

    private val onClearBootingSplash: (AndroidEvent.ClearBootingSplash) -> Unit = {
        viewModelScope.launch {
            hideBootingSplash()
            startPlaySession(_state.value.launchedAppId)
        }
    }

    private var pendingPlaySessionAppId: String? = null
    private var playSession: LibrarySessionTracker.Session? = null
    private var bootingSplashTimeoutJob: Job? = null
    private var bootingSplashArtworkJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    init {
        // Restore persisted screen from SavedStateHandle if available
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        val restoredScreen = when (persistedRoute) {
            PluviaScreen.Home.route -> PluviaScreen.Home
            PluviaScreen.XServer.route -> PluviaScreen.XServer
            PluviaScreen.Settings.route -> PluviaScreen.Settings
            PluviaScreen.Chat.route -> PluviaScreen.Chat
            else -> null
        }

        // Determine initial connection state based on service state
        val initialConnectionState = when {
            SteamService.isConnected -> ConnectionState.CONNECTED
            else -> ConnectionState.CONNECTING
        }

        _state.update {
            it.copy(
                isSteamConnected = SteamService.isConnected,
                hasCrashedLastStart = PrefManager.recentlyCrashed,
                launchedAppId = "",
                currentScreen = restoredScreen,
                connectionState = initialConnectionState,
            )
        }

        // Register event handlers
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.on<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.on<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.on<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
        PluviaApp.events.on<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.on<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.on<SteamEvent.RemotelyDisconnected, Unit>(onRemotelyDisconnected)
        PluviaApp.events.on<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.on<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.on<SteamEvent.LoggedOut, Unit>(onLoggedOut)
        PluviaApp.events.on<AndroidEvent.ServiceReady, Unit>(onServiceReady)

        // Collect theme preferences
        viewModelScope.launch {
            appTheme.themeFlow.collect { value ->
                _state.update { it.copy(appTheme = value) }
            }
        }

        viewModelScope.launch {
            appTheme.paletteFlow.collect { value ->
                _state.update { it.copy(paletteStyle = value) }
            }
        }

        viewModelScope.launch {
            appTheme.customThemeEnabledFlow.collect { value ->
                _state.update { it.copy(customThemeEnabled = value) }
            }
        }

        viewModelScope.launch {
            appTheme.customThemeJsonFlow.collect { value ->
                _state.update { it.copy(customThemeJson = value) }
            }
        }
    }

    override fun onCleared() {
        playSession?.finish()
        PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBackPressed)
        PluviaApp.events.off<AndroidEvent.ExternalGameLaunch, Unit>(onExternalGameLaunch)
        PluviaApp.events.off<AndroidEvent.SetBootingSplashText, Unit>(onSetBootingSplashText)
        PluviaApp.events.off<AndroidEvent.ClearBootingSplash, Unit>(onClearBootingSplash)
        PluviaApp.events.off<SteamEvent.Connected, Unit>(onSteamConnected)
        PluviaApp.events.off<SteamEvent.Disconnected, Unit>(onSteamDisconnected)
        PluviaApp.events.off<SteamEvent.RemotelyDisconnected, Unit>(onRemotelyDisconnected)
        PluviaApp.events.off<SteamEvent.LogonStarted, Unit>(onLoggingIn)
        PluviaApp.events.off<SteamEvent.LogonEnded, Unit>(onLogonEnded)
        PluviaApp.events.off<SteamEvent.LoggedOut, Unit>(onLoggedOut)
        PluviaApp.events.off<AndroidEvent.ServiceReady, Unit>(onServiceReady)
        connectionTimeoutJob?.cancel()
    }

    fun setTheme(value: AppTheme) {
        appTheme.currentTheme = value
    }

    fun setPalette(value: PaletteStyle) {
        appTheme.currentPalette = value
    }

    fun setCustomTheme(json: String) {
        appTheme.setCustomTheme(json = json, enabled = true)
    }

    fun setCustomThemeEnabled(enabled: Boolean) {
        appTheme.setCustomThemeEnabled(enabled)
    }

    fun clearCustomTheme() {
        appTheme.clearCustomTheme()
    }

    fun setAnnoyingDialogShown(value: Boolean) {
        _state.update { it.copy(annoyingDialogShown = value) }
    }

    fun setLoadingDialogVisible(value: Boolean) {
        _state.update { it.copy(loadingDialogVisible = value) }
    }

    fun setLoadingDialogProgress(value: Float) {
        _state.update { it.copy(loadingDialogProgress = value) }
    }

    fun setLoadingDialogMessage(value: String) {
        _state.update { it.copy(loadingDialogMessage = value) }
    }

    fun setHasLaunched(value: Boolean) {
        _state.update { it.copy(hasLaunched = value) }
    }

    private fun showBootingSplash(text: String) {
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = null
        bootingSplashArtworkJob?.cancel()
        _state.update {
            it.copy(
                showBootingSplash = true,
                bootingSplashText = text,
                bootingSplashHeroImageUrl = "",
                bootingSplashTakingLonger = false,
            )
        }
    }

    private fun hideBootingSplash() {
        bootingSplashTimeoutJob?.cancel()
        bootingSplashTimeoutJob = null
        bootingSplashArtworkJob?.cancel()
        bootingSplashArtworkJob = null
        _state.update { it.copy(showBootingSplash = false, bootingSplashTakingLonger = false) }
    }

    fun showGameScreenDuringStartup() {
        if (_state.value.canRevealGameScreen) hideBootingSplash()
    }

    private fun setBootingSplashText(value: String) {
        _state.update { it.withBootingSplashText(value) }
    }

    // Connection state management

    /**
     * Called when starting a reconnection attempt.
     * Sets state to CONNECTING and starts a timeout counter.
     */
    fun startConnecting(message: String? = null) {
        connectionTimeoutJob?.cancel()
        _state.update {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                connectionMessage = message,
                connectionTimeoutSeconds = 0,
            )
        }

        // Start timeout counter
        connectionTimeoutJob = viewModelScope.launch {
            var seconds = 0
            while (seconds < 30 && _state.value.connectionState == ConnectionState.CONNECTING) {
                delay(1000)
                seconds++
                _state.update { it.copy(connectionTimeoutSeconds = seconds) }
            }
        }
    }

    /**
     * Called when user chooses to continue in offline mode.
     * Stops reconnection attempts and allows app to function offline.
     */
    fun continueOffline() {
        connectionTimeoutJob?.cancel()
        _state.update {
            it.copy(
                connectionState = ConnectionState.OFFLINE_MODE,
                connectionMessage = null,
                connectionTimeoutSeconds = 0,
            )
        }
    }

    /**
     * Called when user wants to retry connection.
     * Resets offline mode and triggers reconnection.
     */
    fun retryConnection() {
        if (_state.value.connectionState == ConnectionState.OFFLINE_MODE ||
            _state.value.connectionState == ConnectionState.DISCONNECTED ||
            _state.value.connectionState == ConnectionState.LOGGED_OUT
        ) {
            startConnecting()
        }
    }

    fun setCurrentScreen(currentScreen: String?) {
        // Route matching accounts for query params and path params in templates
        // e.g., "home?offline={offline}" should match Home, "chat/{id}" should match Chat
        val screen = when {
            currentScreen == null -> PluviaScreen.LoginUser
            currentScreen == PluviaScreen.LoginUser.route -> PluviaScreen.LoginUser
            currentScreen.startsWith(PluviaScreen.Home.route) -> PluviaScreen.Home
            currentScreen == PluviaScreen.XServer.route -> PluviaScreen.XServer
            currentScreen == PluviaScreen.Settings.route -> PluviaScreen.Settings
            currentScreen.startsWith("chat") -> PluviaScreen.Chat
            else -> PluviaScreen.LoginUser
        }

        setCurrentScreen(screen)
    }

    fun setCurrentScreen(value: PluviaScreen) {
        _state.update { it.copy(currentScreen = value) }
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = value.route
        if (value == PluviaScreen.XServer && _state.value.showBootingSplash && bootingSplashTimeoutJob == null) {
            bootingSplashTimeoutJob = viewModelScope.launch {
                delay(30_000)
                _state.update {
                    if (it.showBootingSplash && it.currentScreen == PluviaScreen.XServer) {
                        it.copy(bootingSplashTakingLonger = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * Gets the persisted route from SavedStateHandle
     *
     * Returns the route the user was on before process death, or null if:
     * - No route was persisted
     * - The persisted route is LoginUser (not meaningful to restore)
     * - The persisted route is XServer (game session is gone after process death)
     * - The persisted route is Chat (dynamic IDs require special handling)
     *
     * Navigation decisions should be made by the caller based on the current
     * NavController destination, not by tracking internal flags.
     *
     * TODO: reconsider this approach when merging GOG and Epic
     */
    fun getPersistedRoute(): String? {
        val persistedRoute = savedStateHandle.get<String>(KEY_CURRENT_SCREEN_ROUTE)
        return when {
            persistedRoute == null -> null
            persistedRoute == PluviaScreen.LoginUser.route -> null
            persistedRoute == PluviaScreen.XServer.route -> null
            persistedRoute.startsWith("chat") -> null
            else -> persistedRoute
        }
    }

    fun clearPersistedRoute() {
        savedStateHandle[KEY_CURRENT_SCREEN_ROUTE] = PluviaScreen.LoginUser.route
    }

    fun setHasCrashedLastStart(value: Boolean) {
        if (value.not()) {
            PrefManager.recentlyCrashed = false
        }
        _state.update { it.copy(hasCrashedLastStart = value) }
    }

    fun setScreen() {
        _state.update { it.copy(resettedScreen = it.currentScreen) }
    }

    fun setLaunchedAppId(value: String) {
        _state.update { it.copy(launchedAppId = value) }
        if (value.isBlank()) {
            CrashContext.clear()
        } else {
            CrashContext.beginLaunch(value)
        }
    }

    private val _requestedLibraryAppId = MutableStateFlow<String?>(null)
    val requestedLibraryAppId = _requestedLibraryAppId.asStateFlow()

    /** Set when a pinned shortcut/external launch should open the game page instead of launching. */
    fun setRequestedLibraryAppId(value: String?) {
        _requestedLibraryAppId.value = value
    }

    fun setBootToContainer(value: Boolean) {
        _state.update { it.copy(bootToContainer = value) }
    }

    fun setTestGraphics(value: Boolean) {
        _state.update { it.copy(testGraphics = value) }
    }

    fun setDiagnostics(value: Boolean) {
        _state.update { it.copy(diagnostics = value) }
        // Persist so setupXEnvironment can read it directly (see PrefManager.wrapperDiagnostics) rather than the
        // XServerScreen composable threading it as a parameter, which tripped an ART VerifyError on that huge method.
        PrefManager.wrapperDiagnostics = value
    }

    private suspend fun restoreItchInstallerIfNeeded(
        session: app.gamenative.localgames.InstallationSession,
    ) {
        val appId = session.appId ?: return
        if (!appId.startsWith("ITCH_")) return
        val gameId = appId.removePrefix("ITCH_").toLongOrNull() ?: return
        runCatching { itchInstallCoordinator.restorePreviousLaunch(gameId) }
    }

    private suspend fun completeItchInstallerIfNeeded(
        session: app.gamenative.localgames.InstallationSession,
    ) {
        val appId = session.appId ?: return
        if (!appId.startsWith("ITCH_")) return
        val gameId = appId.removePrefix("ITCH_").toLongOrNull() ?: return
        runCatching { itchInstallCoordinator.completeInstallerSession(session) }
        PluviaApp.events.emitJava(
            AndroidEvent.LibraryInstallStatusChanged(gameId.toInt(), GameSource.ITCH),
        )
    }

    fun launchApp(context: Context, appId: String) {
        pendingPlaySessionAppId = null
        viewModelScope.launch {
            // getOrCreateContainerWithOverride also picks up a temporary per-launch config
            // (e.g. from an external Intent launch) — falls back to the persisted container
            // untouched when there's no override, so this is safe unconditionally.
            val container = try {
                withContext(Dispatchers.IO) {
                    ContainerUtils.getOrCreateContainerWithOverride(context, appId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Mostly out of storage on small-storage phones: mkdirs() fails and the game has no
                // prefix. Tell the user instead of letting the launch coroutine take the process down.
                Timber.tag("MainViewModel").e(e, "Could not create the container for $appId")
                setLoadingDialogVisible(false)
                SnackbarManager.show(e.localizedMessage ?: context.getString(R.string.install_failed_title))
                return@launch
            }
            if (container.platform.equals(Container.PLATFORM_ANDROID, ignoreCase = true)) {
                // Native Android (Steam Frame / Lepton) build: no Wine container involved at all.
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                when (withContext(Dispatchers.IO) { AndroidGameLauncher.installAndLaunch(context, gameId) }) {
                    AndroidGameLauncher.Result.InstallStarted ->
                        SnackbarManager.show(context.getString(R.string.android_game_install_started))
                    AndroidGameLauncher.Result.Failed ->
                        SnackbarManager.show(context.getString(R.string.android_game_launch_failed))
                    AndroidGameLauncher.Result.Launched -> librarySessions.recordPlayed(appId)
                }
                return@launch
            }

            setLaunchedAppId(appId)
            CrashContext.setStage("preparing_launch")
            showBootingSplash(context.getString(R.string.game_startup_preparing))
            PluviaApp.events.emit(AndroidEvent.SetAllowedOrientation(PrefManager.allowedOrientation))

            bootingSplashArtworkJob = viewModelScope.launch artwork@{
                val heroUrl = try {
                    resolveLaunchArtwork(appId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.w(e, "Could not load launch artwork")
                    return@artwork
                }
                _state.update { it.copy(bootingSplashHeroImageUrl = heroUrl) }
            }

            try {
                withContext(Dispatchers.IO) {
                    prepareGameEnvironment(context, appId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val message = context.getString(
                    R.string.game_startup_preparation_failed,
                    e.localizedMessage ?: e.javaClass.simpleName,
                )
                onGameLaunchError(message)
                setLoadingDialogVisible(false)
                SnackbarManager.show(message)
                return@launch
            }
            LaunchTrace.stage(LaunchStage.PRELAUNCH_SETUP, "success")
            CrashContext.update(launchStage = "launching_game")

            val mode = container.getExtra(LocalContainerLaunch.EXTRA_MODE)
            val isInstaller = mode == LocalContainerLaunch.MODE_INSTALLER_EXE_A ||
                mode == LocalContainerLaunch.MODE_INSTALLER_MSI_A
            setBootingSplashText(
                context.getString(
                    if (isInstaller) R.string.installer_running_splash else R.string.game_startup_starting,
                ),
            )
            if (!_state.value.bootToContainer && !_state.value.testGraphics && !isInstaller) {
                pendingPlaySessionAppId = appId
            }
            _uiEvent.send(MainUiEvent.LaunchApp)
        }
    }

    private suspend fun resolveLaunchArtwork(appId: String): String =
        withContext(Dispatchers.IO) {
            val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            when (gameSource) {
                GameSource.STEAM -> {
                    val steamApp = SteamService.getAppInfoOf(gameId)
                    steamApp?.getHeroUrl()?.ifEmpty { steamApp.headerUrl } ?: ""
                }
                GameSource.GOG -> {
                    val game = GOGService.getGOGGameOf(gameId.toString())
                    game?.backgroundUrl?.ifEmpty { game.imageUrl } ?: ""
                }
                GameSource.EPIC -> {
                    val game = EpicService.getEpicGameOf(gameId)
                    game?.artPortrait?.ifEmpty { game.artCover.ifEmpty { game.artSquare } } ?: ""
                }
                GameSource.AMAZON -> {
                    val game = AmazonService.getAmazonGameByAppId(gameId)
                    game?.heroUrl?.ifEmpty { game.artUrl } ?: ""
                }
                GameSource.CUSTOM_GAME -> {
                    val folderPath = CustomGameScanner.getFolderPathFromAppId(appId) ?: return@withContext ""
                    val folder = java.io.File(folderPath)
                    val heroFile = folder.listFiles()?.firstOrNull { file ->
                        file.isFile &&
                            file.name.startsWith("steamgriddb_hero", ignoreCase = true) &&
                            !file.name.contains("grid_", ignoreCase = true) &&
                            (file.name.endsWith(".png", ignoreCase = true) ||
                                file.name.endsWith(".jpg", ignoreCase = true) ||
                                file.name.endsWith(".webp", ignoreCase = true))
                    }
                    heroFile?.let { android.net.Uri.fromFile(it).toString() } ?: ""
                }
                GameSource.ITCH -> {
                    itchLibraryRepository.getGame(gameId.toLong())?.coverUrl.orEmpty()
                }
                else -> ""
            }
        }

    private suspend fun prepareGameEnvironment(context: Context, appId: String) {
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        CrashContext.update(
            gameName = runCatching { ContainerUtils.resolveGameName(appId) }.getOrNull(),
            containerId = container.id,
            runtime = buildString {
                append("Wine/Proton=")
                append(container.getWineVersion())
                append("; graphics=")
                append(container.getGraphicsDriver())
                append(" ")
                append(container.getGraphicsDriverVersion())
                append("; DXVK/VKD3D=")
                append(container.getDXWrapper())
            },
            gpu = runCatching { GPUInformation.getRenderer(context) }.getOrNull(),
            launchStage = "preparing_runtime",
        )
        val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
        if (gameSource == GameSource.STEAM) {
            if (container.isLaunchRealSteam() || container.isLaunchBionicSteam()) {
                SteamUtils.restoreSteamApi(context, appId)
            } else {
                val offline = _offline.value
                if (container.isUseLegacyDRM) {
                    SteamUtils.replaceSteamApi(context, appId, offline)
                } else {
                    SteamUtils.replaceSteamclientDll(context, appId, offline)
                }
            }
        }
    }

    private fun startPlaySession(appId: String) {
        if (appId != pendingPlaySessionAppId) return
        pendingPlaySessionAppId = null
        playSession?.finish()
        playSession = librarySessions.start(appId)
    }

    fun exitSteamApp(context: Context, appId: String, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (pendingPlaySessionAppId == appId) pendingPlaySessionAppId = null
            if (playSession?.appId == appId) {
                playSession?.finish()
                playSession = null
            }
            try {
                Timber.tag("Exit").i("Exiting appId: $appId")
                hideBootingSplash()
                // Check if we have a temporary override before doing anything
                val hadTemporaryOverride = IntentLaunchManager.hasTemporaryOverride(appId)

                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
                val gameSource = ContainerUtils.extractGameSourceFromContainerId(appId)
                Timber.tag("Exit").i("Got game id: $gameId")
                ActiveGameRegistry.clearIfMatches(gameId)
                val offline = isOffline.value
                val appContext = context.applicationContext
                if (gameSource == GameSource.STEAM || gameSource == GameSource.GOG || gameSource == GameSource.EPIC) {
                    ExitCloudSyncQueue.process.start(appId) {
                        handleExitCloudSync(appContext, appId, gameId, gameSource, offline)
                    }.join()
                } else {
                    SteamService.notifyRunningProcesses()
                }

                if (gameSource == GameSource.CUSTOM_GAME || gameSource == GameSource.ITCH) {
                    when (val result = LocalInstallerCompletionCoordinator.handleInstallerExit(context, appId)) {
                        LocalInstallerCompletionCoordinator.Result.NotAnInstallerSession -> Unit
                        is LocalInstallerCompletionCoordinator.Result.Completed -> {
                            completeItchInstallerIfNeeded(result.session)
                            _uiEvent.send(MainUiEvent.InstallerCompleted(result.session.title))
                        }
                        is LocalInstallerCompletionCoordinator.Result.NeedsExecutableSelection -> {
                            _uiEvent.send(
                                MainUiEvent.SelectInstallerExecutable(
                                    sessionId = result.session.id,
                                    title = result.session.title,
                                    candidates = result.session.candidateExecutablePaths,
                                ),
                            )
                        }
                        is LocalInstallerCompletionCoordinator.Result.Failed -> {
                            restoreItchInstallerIfNeeded(result.session)
                            _uiEvent.send(
                                MainUiEvent.InstallerCompletionFailed(
                                    title = result.session.title,
                                    reason = result.reason,
                                    sessionId = result.session.id,
                                ),
                            )
                        }
                    }
                }

                // Prompt user to save temporary container configuration if one was applied
                if (hadTemporaryOverride) {
                    PluviaApp.events.emit(AndroidEvent.PromptSaveContainerConfig(appId))
                    // Dialog handler in PluviaMain manages the save/discard logic
                }
            } finally {
                try {
                    onComplete?.invoke()
                } finally {
                    CrashContext.clearIfMatches(appId)
                    if (PluviaApp.pendingRelaunchAppId == appId) {
                        PluviaApp.pendingRelaunchAppId = null
                        launchApp(context, appId)
                    }
                }
            }
        }
    }

    fun selectInstalledExecutable(
        context: Context,
        sessionId: String,
        relativePath: String,
    ) {
        viewModelScope.launch {
            runCatching {
                LocalInstallerCompletionCoordinator.selectExecutable(context, sessionId, relativePath)
            }.onSuccess { result ->
                if (result is LocalInstallerCompletionCoordinator.Result.Completed) {
                    completeItchInstallerIfNeeded(result.session)
                    _uiEvent.send(MainUiEvent.InstallerCompleted(result.session.title))
                }
            }.onFailure { error ->
                _uiEvent.send(
                    MainUiEvent.InstallerCompletionFailed(
                        title = "Installation",
                        reason = error.message ?: "Could not select the game executable",
                    ),
                )
            }
        }
    }

    fun browseAllInstallerExecutables(context: Context, sessionId: String) {
        viewModelScope.launch {
            runCatching {
                LocalInstallerCompletionCoordinator.browseAllExecutables(context, sessionId)
            }.onSuccess { result ->
                when (result) {
                    is LocalInstallerCompletionCoordinator.Result.NeedsExecutableSelection -> {
                        _uiEvent.send(
                            MainUiEvent.SelectInstallerExecutable(
                                sessionId = result.session.id,
                                title = result.session.title,
                                candidates = result.session.candidateExecutablePaths,
                            ),
                        )
                    }
                    is LocalInstallerCompletionCoordinator.Result.Failed -> {
                        _uiEvent.send(
                            MainUiEvent.InstallerCompletionFailed(
                                title = result.session.title,
                                reason = result.reason,
                                sessionId = result.session.id,
                            ),
                        )
                    }
                    else -> Unit
                }
            }.onFailure { error ->
                _uiEvent.send(
                    MainUiEvent.InstallerCompletionFailed(
                        title = "Installation",
                        reason = error.message ?: "Could not scan the installed files",
                    ),
                )
            }
        }
    }

    fun retryInstallerSession(context: Context, sessionId: String) {
        viewModelScope.launch {
            runCatching {
                LocalInstallerCompletionCoordinator.retryInstaller(context, sessionId)
            }.onSuccess { appId ->
                if (appId.startsWith("ITCH_")) {
                    // The failed update restored the installed game in the container;
                    // point it back at the installer before relaunching it.
                    runCatching {
                        InstallationSessionStore(context).load(sessionId)?.let { session ->
                            itchInstallCoordinator.armInstallerContainer(session)
                        }
                    }
                }
                _uiEvent.send(MainUiEvent.RetryInstallerLaunch(appId))
            }.onFailure { error ->
                _uiEvent.send(
                    MainUiEvent.InstallerCompletionFailed(
                        title = "Installation",
                        reason = error.message ?: "Could not restart the installer",
                    ),
                )
            }
        }
    }

    fun recoverInstallerSessions(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            // Finish or discard itch.io ZIP updates interrupted on a previous run
            // before looking at installer sessions: the old version must stay launchable.
            runCatching { itchInstallCoordinator.recoverInterruptedInstalls() }
            val sessions = InstallationSessionStore(context).loadAll()
            val pendingSelection = sessions.firstOrNull {
                it.state == InstallationState.CANDIDATE_SELECTION && it.candidateExecutablePaths.isNotEmpty()
            }
            if (pendingSelection != null) {
                // A stale session whose container (or every candidate exe) is gone
                // must not prompt on every launch: fail it once with a retry path.
                val container = pendingSelection.appId
                    ?.let { runCatching { ContainerUtils.getContainer(context, it) }.getOrNull() }
                val driveC = container?.rootDir?.let { java.io.File(it, ".wine/drive_c") }
                val anyCandidateExists = driveC != null && pendingSelection.candidateExecutablePaths.any { rel ->
                    java.io.File(driveC, rel.replace('\\', '/').trimStart('/')).isFile
                }
                if (container == null || !anyCandidateExists) {
                    val store = InstallationSessionStore(context)
                    val failed = pendingSelection.transitionTo(
                        InstallationState.FAILED,
                        error = "Installed files are no longer available. Run the installer again to reinstall.",
                    )
                    store.save(failed)
                    _uiEvent.send(
                        MainUiEvent.InstallerCompletionFailed(
                            title = failed.title,
                            reason = requireNotNull(failed.lastError),
                            sessionId = failed.id,
                        ),
                    )
                } else {
                    _uiEvent.send(
                        MainUiEvent.SelectInstallerExecutable(
                            sessionId = pendingSelection.id,
                            title = pendingSelection.title,
                            candidates = pendingSelection.candidateExecutablePaths,
                        ),
                    )
                }
                return@launch
            }

            val awaitingResult = sessions.firstOrNull {
                it.state == InstallationState.AWAITING_RESULT && it.appId != null
            }
            if (awaitingResult != null) {
                when (val result = LocalInstallerCompletionCoordinator.handleInstallerExit(context, awaitingResult.appId!!)) {
                    LocalInstallerCompletionCoordinator.Result.NotAnInstallerSession -> Unit
                    is LocalInstallerCompletionCoordinator.Result.Completed -> {
                        completeItchInstallerIfNeeded(result.session)
                        _uiEvent.send(MainUiEvent.InstallerCompleted(result.session.title))
                    }
                    is LocalInstallerCompletionCoordinator.Result.NeedsExecutableSelection -> {
                        _uiEvent.send(
                            MainUiEvent.SelectInstallerExecutable(
                                result.session.id,
                                result.session.title,
                                result.session.candidateExecutablePaths,
                            ),
                        )
                    }
                    is LocalInstallerCompletionCoordinator.Result.Failed -> {
                        restoreItchInstallerIfNeeded(result.session)
                        _uiEvent.send(
                            MainUiEvent.InstallerCompletionFailed(
                                title = result.session.title,
                                reason = result.reason,
                                sessionId = result.session.id,
                            ),
                        )
                    }
                }
                return@launch
            }

            val interrupted = sessions.firstOrNull {
                it.state == InstallationState.INSTALLER_RUNNING && it.appId != null
            }
            if (interrupted != null) {
                val result = LocalInstallerCompletionCoordinator.markInterrupted(context, interrupted.id)
                if (result is LocalInstallerCompletionCoordinator.Result.Failed) {
                    restoreItchInstallerIfNeeded(result.session)
                    _uiEvent.send(
                        MainUiEvent.InstallerCompletionFailed(
                            title = result.session.title,
                            reason = result.reason,
                            sessionId = result.session.id,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun handleExitCloudSync(context: Context, appId: String, gameId: Int, gameSource: GameSource, offline: Boolean) {
        try {
            SteamService.notifyRunningProcesses()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Exit").w(e, "Failed to update Steam's playing status for $appId")
        }
        try {
            if (ContainerUtils.isLocalSavesOnly(context, appId)) return
            if (offline && gameSource != GameSource.STEAM) return
            when (gameSource) {
                GameSource.GOG -> {
                    if (!app.gamenative.service.gog.GOGService.syncCloudSaves(context, appId, preferredAction = "upload")) {
                        Timber.tag("Exit").w("GOG save upload did not complete for $appId")
                    }
                }
                GameSource.EPIC -> {
                    if (!EpicCloudSavesManager.syncCloudSaves(context, gameId, preferredAction = "upload-if-changed")) {
                        Timber.tag("Exit").w("Epic save upload did not complete for $appId")
                    }
                }
                GameSource.STEAM -> {
                    val container = ContainerUtils.getContainer(context, appId)
                    SteamService.closeApp(context, gameId, offline) { prefix ->
                        PathType.from(prefix).toAbsPath(container, gameId, SteamService.userSteamId!!.accountID)
                    }.await()
                }
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Exit").e(e, "Cloud save sync failed for $appId; the next launch will sync again")
        }
    }

    fun onWindowMapped(context: Context, window: Window, appId: String) {
        viewModelScope.launch {
            // Hide the booting splash when a window is mapped
            hideBootingSplash()

            val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
            val isApplicationWindow = window.isApplicationWindow() &&
                !WineProcessSnapshotHelper.isSystemProcessName(window.className)

            if (ContainerUtils.extractGameSourceFromContainerId(appId) != GameSource.STEAM) {
                if (isApplicationWindow) startPlaySession(appId)
                return@launch
            }

            val isAlreadyRunning = ActiveGameRegistry.get()?.appId == gameId
            if (isAlreadyRunning && pendingPlaySessionAppId != appId) return@launch
            val matchesLaunchConfig = SteamService.getWindowsLaunchInfos(gameId).any {
                val gameExe = Paths.get(it.executable.replace('\\', '/')).name.lowercase()
                val windowExe = window.className.lowercase()
                gameExe == windowExe
            }
            val isGameWindow = matchesLaunchConfig || isApplicationWindow
            if (isGameWindow) startPlaySession(appId)

            if (isAlreadyRunning) return@launch

            SteamService.getAppInfoOf(gameId)?.let {
                if (isGameWindow) {
                    val steamProcessId = Process.myPid()
                    val processes = mutableListOf<AppProcessInfo>()
                    var currentWindow: Window = window
                    do {
                        var parentWindow: Window? = currentWindow.parent
                        val process = if (parentWindow != null && parentWindow.className.lowercase() != "explorer.exe") {
                            val processId = currentWindow.processId
                            val parentProcessId = parentWindow.processId
                            currentWindow = parentWindow

                            AppProcessInfo(processId, parentProcessId, false)
                        } else {
                            parentWindow = null

                            AppProcessInfo(currentWindow.processId, steamProcessId, true)
                        }
                        processes.add(process)
                    } while (parentWindow != null)

                    val installedBranch = SteamService.getInstalledApp(gameId)?.branch ?: "public"
                    GameProcessInfo(appId = gameId, branch = installedBranch, processes = processes).let {
                        // Only notify Steam if we're not using real Steam
                        // When launchRealSteam is true, let the real Steam client handle the "game is running" notification
                        val shouldLaunchRealSteam = try {
                            val container = ContainerUtils.getContainer(context, appId)
                            container.isLaunchRealSteam() || container.isLaunchBionicSteam()
                        } catch (e: Exception) {
                            // Container might not exist, default to notifying Steam
                            false
                        }

                        if (!shouldLaunchRealSteam) {
                            ActiveGameRegistry.set(it)
                            SteamService.notifyRunningProcesses(it)
                        } else {
                            ActiveGameRegistry.clear()
                            Timber.tag("MainViewModel").i("Skipping Steam process notification - real Steam will handle this")
                        }
                    }
                }
            }
        }
    }

    fun onGameLaunchError(error: String) {
        viewModelScope.launch {
            pendingPlaySessionAppId = null
            // Hide the splash screen if it's still showing
            hideBootingSplash()
            LaunchTrace.finish("failed")
            Timber.tag("MainViewModel").e("Game launch error: $error")
        }
    }

}
