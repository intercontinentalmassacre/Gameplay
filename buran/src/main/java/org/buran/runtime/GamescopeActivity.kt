package org.buran.runtime

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Process as AndroidProcess
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.buran.runtime.display.GamescopeGameWindow
import org.buran.runtime.display.StandaloneMouse
import org.buran.runtime.display.GamescopeHostView
import org.buran.runtime.display.GamescopeRuntime
import org.buran.runtime.display.GamescopePresentFrames
import org.buran.runtime.hud.GamescopePerformanceHud
import org.buran.runtime.hud.PerformanceHudConfig
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.concurrent.thread

class GamescopeActivity : BuranActivity(), SurfaceHolder.Callback {
    private lateinit var host: GamescopeHostView
    private lateinit var status: TextView
    private lateinit var returnToLibrary: Button
    private lateinit var cancelDependencyInstall: Button
    private var performanceHud: GamescopePerformanceHud? = null
    private var performanceHudEnabled = false
    private var performanceHudWantsFps = true
    private var reportOutputFps = false
    @Volatile private var fpsDemand = FpsDemand.NONE
    @Volatile private var performanceFps: Float? = null
    @Volatile private var generatedFps: Int? = null
    private enum class FpsDemand { NONE, UPPER, LOWER }
    private var sessionMenu: GameSessionMenu? = null
    private val gamepadMenuShortcut = GamepadMenuShortcut(::canOpenGameplayMenu, ::openQuickMenu, ::forwardGameKey)
    private var menuAvailable = false
    @Volatile private var menuOpen = false
    @Volatile private var guestPaused = false
    private var session: Thread? = null
    private val sessionSignal = Any()
    private var cleaningUp = false
    private var ownsProcess = false
    private val gameId get() = intent.getStringExtra(GameLaunchResult.GAME_ID)
    private var backCallback: OnBackInvokedCallback? = null
    private var gameplayInput: GameplayInputChannel? = null
    private var companionInput: GameplayInputChannel? = null
    private val touchSensitivity: Float
        get() = intent.getFloatExtra("touchSensitivity", 1f).takeIf { it.isFinite() && it > 0f } ?: 1f
    private val companionSessionId = UUID.randomUUID().toString()
    @Volatile private var mangoHudEnabled = false
    @Volatile private var dualScreenControls = false
    private var mangoHudAvailable = false
    private var mangoHudShown = false
    private var physicalGamepad: PhysicalGamepad? = null
    @Volatile private var sessionInputReady = false
    private var dependencyInputReady = false
    @Volatile private var closing = false
    @Volatile private var resumed = false
    @Volatile private var visible = false
    @Volatile private var stopRequested = false
    private var restartRequested = false
    @Volatile private var hostFailure: Throwable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        dualScreenControls = intent.getBooleanExtra(GAMEPLAY_DUAL_SCREEN, false)
        if (gameId != null && savedInstanceState != null) {
            // Android restored the Activity, but the live game session was lost.
            finish()
            return
        }
        val processSuffix = if (isBuranEmbedded) ":buran_gamescope" else ":gamescope"
        if (Application.getProcessName() != packageName + processSuffix || processLease != null) {
            failToAcquireProcess()
            return
        }
        processLease = try {
            lock(this)
        } catch (error: Exception) {
            failToAcquireProcess(error.localizedMessage)
            return
        }
        if (processLease == null) {
            failToAcquireProcess()
            return
        }
        ownsProcess = true
        if (gameId != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = "Подготовка экрана Gamescope…"
        }
        host = GamescopeHostView(this, ::startSession, ::onHostFailure, driverOverride = {
            TurnipDrivers.fromGameplay(this, intent.getBundleExtra(DxvkComponents.EXTRA))
        }, framegen2x = intent.getBooleanExtra("buranFramegen2x", false),
            onFramegenStatus = ::publishFramegenStatus)
        host.holder.addCallback(this)
        setContentView(FrameLayout(this).apply {
            addView(host)
            if (gameId != null) {
                val margin = (8 * resources.displayMetrics.density).toInt()
                performanceHud = GamescopePerformanceHud(context, { performanceFps }, { generatedFps }).also { hud ->
                    addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                        marginStart = margin
                        topMargin = margin
                    })
                }
            }
            addView(status)
            returnToLibrary = Button(context).apply {
                text = "Вернуться в Buran"
                isAllCaps = false
                visibility = View.GONE
                setOnClickListener { finish() }
            }
            addView(returnToLibrary, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            if (intent.getBooleanExtra("standaloneMouse", false)) host.mouse = StandaloneMouse(this, host)
            else if (intent.hasExtra(GAMEPLAY_SESSION_STATUS)) {
                host.mouse = StandaloneMouse(this, host, showToggle = false, touchSensitivity = touchSensitivity)
            }
            cancelDependencyInstall = Button(context).apply {
                setText(R.string.buran_cancel_dependency_install)
                isAllCaps = false
                visibility = View.GONE
                setOnClickListener { requestStop() }
            }
            addView(cancelDependencyInstall, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            if (gameId != null) {
                sessionMenu = GameSessionMenu(this, ::setMenuOpen, ::toggleLocalPerformanceHud,
                    { setMangoHudEnabled(!mangoHudEnabled) }, ::setCompanionFpsLimit, ::requestStop)
            }
        })
        if (gameId != null && Build.VERSION.SDK_INT >= 33) {
            backCallback = OnBackInvokedCallback { gameBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        }
    }

    private fun startSession(view: GamescopeHostView) {
        if (closing || stopRequested || session != null) return
        val gameId = gameId
        val engine = WindowsEngine.available().firstOrNull { it.name == intent.getStringExtra(engineExtra) }
            ?: WindowsEngine.selected(this)
        val desktop = intent.getBooleanExtra(desktopExtra, false)
        val dxvkCheck = intent.getBooleanExtra(dxvkExtra, false)
        val gameDxvk = intent.getBooleanExtra(gameDxvkExtra, false)
        val requiresMono = intent.getBooleanExtra(monoExtra, false)
        val gameArguments = intent.getStringArrayListExtra(gameArgumentsExtra).orEmpty()
        session = thread(name = "Buran Gamescope") {
            var compositor: Process? = null
            var wine: Process? = null
            var gameWindow: GamescopeGameWindow? = null
            var audio: GameAudio? = null
            var mangoHud: MangoHudOverlay? = null
            var fpsTelemetry: GamescopePresentFrames? = null
            var gameExit: File? = null
            var dependencyProgress: File? = null
            var containerState: File? = null
            var bionicSteam: BionicSteamRuntime? = null
            var exitCode: Int? = null
            var failure: Throwable? = null
            try {
                val game = gameId?.let { id ->
                    InstalledGame(id, requireNotNull(intent.getStringExtra("gameExe")), intent.getIntExtra("gameBits", 0))
                }
                val external = intent.getBundleExtra(externalExtra)?.let { source ->
                    val target = checkNotNull(game)
                    ExternalWindowsLaunch.resolve(
                        requireNotNull(source.getString("key")), File(requireNotNull(source.getString("directory"))),
                        target.exe, target.bits, source.getString("cwd"), source.getStringArrayList("env").orEmpty(),
                        steamAppId = source.getInt(GAMEPLAY_STEAM_APP_ID).takeIf { it > 0 },
                        steamUserDirectory = source.getString("steamUserDirectory")?.let(::File),
                        steamRemoteDirectory = source.getString("steamRemoteDirectory")?.let(::File),
                    ).also { check(it.game == target) { "Путь установки изменился" } }
                }
                val container = intent.getStringExtra(containerExtra)?.let { Containers.find(applicationContext, it) }
                    ?: game?.let { Containers.selected(applicationContext, it.id, engine, external != null) }
                containerState = container?.let {
                    require(it.engine == engine) { "Контейнер использует другой движок" }
                    Containers.directory(applicationContext, it)
                }
                val state = containerState
                val cpu = BuranCpuSelection.resolve(applicationContext, intent.getBundleExtra(BuranCpuSelection.EXTRA))
                bionicSteam = intent.getBundleExtra(BionicSteamRuntime.EXTRA)?.let {
                    require(external != null)
                    BionicSteamRuntime.resolve(applicationContext, it)
                }
                if (bionicSteam == null && engine == WindowsEngine.FEX && state != null && WindowsProbe.needsPreparation(state)) {
                    runOnUiThread { if (!closing) status.text = "Подготавливаем системные файлы Wine…" }
                    WindowsProbe.prepareContainer(applicationContext, state, cpu.fexSystem32)
                    if (closing || stopRequested || hostFailure != null) return@thread
                    runOnUiThread { if (!closing) status.text = "Подготовка экрана Gamescope…" }
                }
                val process = GamescopeRuntime.start(applicationContext, view.runtimeDirectory, view.displayName,
                    sharedPixmaps = bionicSteam != null && game != null)
                compositor = process
                if (closing || stopRequested || hostFailure != null) return@thread
                val x11 = GamescopeRuntime.awaitX11(
                    applicationContext, process,
                    wineDrawsDesktopFrames = desktop && (bionicSteam != null || engine == WindowsEngine.FEX) && !dxvkCheck,
                )
                if (closing || stopRequested || hostFailure != null) return@thread
                if (game != null && !dxvkCheck && bionicSteam == null) {
                    gameExit = File.createTempFile("buran-exit-", ".bin", File(x11.getValue("XAUTHORITY")).parentFile!!)
                }
                val audioSocket = if (gameDxvk && game != null) {
                    File(x11.getValue("XAUTHORITY")).parentFile!!.resolve("audio.pcm")
                } else null
                val display = x11.toMutableMap()
                if (audioSocket != null) display["BURAN_AUDIO_SOCKET"] = audioSocket.path
                if (game != null && gameDxvk && engine == WindowsEngine.BOX64) {
                    mangoHud = MangoHudOverlay(companionSessionId)
                    display[MangoHudOverlay.CONTROL] = mangoHud.socketName
                }
                val needsLocalBoxController = gameDxvk && engine == WindowsEngine.BOX64 &&
                    PhysicalGamepad.gameplaySharedFile(applicationContext) == null
                if (game != null && (bionicSteam != null || needsLocalBoxController)) {
                    val directory = checkNotNull(File(x11.getValue("XAUTHORITY")).parentFile)
                    val controllerFile = if (bionicSteam != null) {
                        val base = directory.resolve("buran-pad")
                        val shared = base.resolve("gamepad_shm")
                        check(shared.mkdirs() || shared.isDirectory)
                        display["EVSHIM_BASE_PATH"] = base.path
                        shared.resolve("gamepad.mem")
                    } else {
                        directory.resolve(PhysicalGamepad.FILE_NAME)
                    }
                    val controller = PhysicalGamepad(applicationContext, controllerFile)
                    runOnUiThread {
                        if (closing || stopRequested || hostFailure != null) {
                            controller.close()
                        } else {
                            physicalGamepad = controller
                            controller.listen()
                            updatePhysicalGamepad()
                        }
                    }
                }
                val gameExecutable = game?.let { intent.getStringExtra(windowExecutableExtra) ?: it.exe }
                gameWindow = gameExecutable?.let { executable ->
                    try {
                        GamescopeRuntime.watchGameWindow(process, x11, executable, desktop)
                    } catch (error: Exception) {
                        Log.w("BuranWindow", "Could not monitor game window", error)
                        null
                    }
                }
                val nativeSteam = bionicSteam
                val dependencies = external?.let {
                    SteamInstallDependencies.resolve(intent.getBundleExtra(SteamInstallDependencies.EXTRA), it)
                }
                if (dependencies != null && nativeSteam == null && engine != WindowsEngine.FEX) {
                    dependencyProgress = File.createTempFile(
                        "steam-dependencies-", ".status", File(x11.getValue("XAUTHORITY")).parentFile!!,
                    )
                }
                if (desktop) showDependencyInput()
                val guest = if (nativeSteam != null) {
                    nativeSteam.start(
                        applicationContext, checkNotNull(external), display, gameArguments, dependencies,
                        onDependencyInstalling = ::showDependencyInput,
                        isCancelled = { closing || stopRequested || hostFailure != null },
                        onShaderPrewarm = ::showShaderPrewarm,
                    )
                } else if (dxvkCheck) BoxRuntime.startDxvk(applicationContext, x11)
                    else engine.window(
                        applicationContext, display, if (external == null) game else null,
                        desktop, requiresMono, gameDxvk, gameArguments, external = external, desktopExit = gameExit,
                        containerState = containerState,
                        cpu = cpu,
                        installedDxvk = if (gameDxvk) {
                            DxvkComponents.resolve(applicationContext, intent.getBundleExtra(DxvkComponents.EXTRA))
                        } else null,
                        dependencies = dependencies,
                        dependencyProgress = dependencyProgress,
                        onDependencyInstalling = ::showDependencyInput,
                    )
                wine = guest
                dependencyProgress?.let { progress ->
                    var inputRequested = false
                    while (!guest.waitFor(100, TimeUnit.MILLISECONDS)) {
                        if (closing || stopRequested || hostFailure != null) return@thread
                        check(process.isAlive) { "Gamescope завершился с кодом ${process.exitValue()}" }
                        when (progress.readText().trim()) {
                            "INSTALLING" -> if (!inputRequested) {
                                inputRequested = true
                                showDependencyInput()
                            }
                            "COMPLETE" -> break
                        }
                    }
                }
                if (audioSocket != null) {
                    audio = GameAudio(audioSocket) { gameplayForeground() && !menuOpen && !guestPaused }
                }
                if (requiresMono && game != null && !BoxRuntime.monoInstalled(checkNotNull(containerState))) {
                    runOnUiThread { if (!closing) status.text = "Устанавливаем Wine Mono для ${game.exe.substringAfterLast('/')}…" }
                    while (!guest.waitFor(250, TimeUnit.MILLISECONDS) &&
                        !BoxRuntime.monoInstalled(containerState)
                    ) {
                        if (closing || stopRequested || hostFailure != null) return@thread
                        check(process.isAlive) { "Gamescope завершился с кодом ${process.exitValue()}" }
                    }
                }
                sessionInputReady = guest.isAlive
                runOnUiThread {
                    if (!closing && !stopRequested && hostFailure == null) {
                        hideDependencyInput()
                        status.visibility = View.GONE
                        host.mouse?.ready()
                        menuAvailable = sessionMenu != null
                        sessionMenu?.display(menuAvailable, menuOpen)
                        publishGameplayInput()
                        updateGameInput()
                        updatePerformanceHud()
                        if (sessionInputReady) reportGameplaySessionReady()
                    }
                }
                val pauseInBackground = intent.getBooleanExtra(pauseInBackgroundExtra, true)
                var appliedLimit = 0
                var mangoHudAvailable = false
                var reportedMangoHud = false
                var sendingFps = false
                var nextFpsReport = 0L
                var measuringFps = false
                while (!guest.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (closing || stopRequested || hostFailure != null) return@thread
                    val demand = fpsDemand
                    val measureFps = demand != FpsDemand.NONE
                    if (measureFps != measuringFps) {
                        measuringFps = measureFps
                        if (measureFps && gameExecutable != null) {
                            fpsTelemetry = runCatching {
                                GamescopeRuntime.watchPresentFrames(process, x11, gameExecutable, desktop)
                            }.onFailure { Log.w("BuranHud", "Could not subscribe to game presentations", it) }.getOrNull()
                        } else {
                            runCatching { fpsTelemetry?.close() }
                            fpsTelemetry = null
                        }
                    }
                    performanceFps = runCatching { fpsTelemetry?.readFps() }.onFailure {
                        Log.w("BuranHud", "Lost game presentation telemetry", it)
                        runCatching { fpsTelemetry?.close() }
                        fpsTelemetry = null
                    }.getOrNull()
                    val sendFps = demand == FpsDemand.LOWER
                    val now = SystemClock.elapsedRealtime()
                    generatedFps = if (measureFps) view.sampleGeneratedFps(now) else null
                    if (sendFps != sendingFps || (sendFps && now >= nextFpsReport)) {
                        sendingFps = sendFps
                        nextFpsReport = now + 1_000L
                        reportPerformanceFps(if (sendFps) performanceFps else null, if (sendFps) generatedFps else null)
                    }
                    if (mangoHud != null && game != null && !guestPaused && (!dualScreenControls || mangoHud.mayBeEnabled)) {
                        val desired = mangoHudEnabled && !dualScreenControls
                        val available = mangoHud.update(desired) { BoxRuntime.mangoHudGuestPids(guest, game.exe) }
                        val enabled = available && desired
                        if (available != mangoHudAvailable || enabled != reportedMangoHud) {
                            reportMangoHudStatus(available, enabled)
                            mangoHudAvailable = available
                            reportedMangoHud = enabled
                        }
                    }
                    val limit = companionFpsLimit
                    if (limit != appliedLimit) {
                        view.setFpsLimit(limit)
                        try {
                            GamescopeRuntime.setFpsLimit(process, x11, limit)
                        } catch (error: Exception) {
                            Log.w("BuranGamescope", "Could not set Gamescope FPS limit", error)
                        }
                        appliedLimit = limit
                    }
                    check(process.isAlive) { "Gamescope завершился с кодом ${process.exitValue()}" }
                    gameWindow?.let { monitor ->
                        val finished = try {
                            monitor.isReady().also { if (it) sendEvent("window_ready") }
                        } catch (error: Exception) {
                            Log.w("BuranWindow", "Lost game window monitor", error)
                            true
                        }
                        if (finished) {
                            runCatching { monitor.close() }
                            gameWindow = null
                        }
                    }
                    if (game != null && gameDxvk && engine == WindowsEngine.BOX64) {
                        runPauseControl(guest, pauseInBackground)
                    }
                }
                sessionInputReady = false
                if (closing || stopRequested || hostFailure != null) return@thread
                val wineCode = guest.exitValue()
                val marker = gameExit
                val log = File(buranFilesDir, when {
                    bionicSteam != null -> "bionic-steam-stderr.txt"
                    dxvkCheck || gameDxvk -> "dxvk-stderr.txt"
                    else -> "window-stderr.txt"
                })
                exitCode = if (marker == null) wineCode else {
                    val code = try {
                        DataInputStream(marker.inputStream()).use { result ->
                            val code = Integer.reverseBytes(result.readInt())
                            code.takeIf { result.read() == -1 }
                        }
                    } catch (error: IOException) {
                        Log.w("BuranWindow", "Could not read EXE exit status", error)
                        null
                    }
                    checkNotNull(code) {
                        wineFailure("Wine", wineCode, log) + "\nКод завершения EXE неизвестен"
                    }
                }
                check(exitCode == 0) {
                    val program = if (marker != null) "EXE" else "Wine"
                    wineFailure(program, checkNotNull(exitCode), log)
                }
                check(wineCode == 0) { wineFailure("Wine", wineCode, log) }
            } catch (e: Exception) {
                if (!closing && (!stopRequested || exitCode != null)) {
                    Log.w("BuranGamescope", "Guest launch failed", e)
                    failure = e
                }
            } finally {
                runCatching { fpsTelemetry?.close() }
                performanceFps = null
                generatedFps = null
                sessionInputReady = false
                runOnUiThread {
                    hideDependencyInput()
                    closeGameplayInput()
                    updateGameInput()
                }
                synchronized(sessionSignal) {
                    cleaningUp = true
                    Thread.interrupted()
                }
                runCatching { gameWindow?.close() }
                audio?.close()
                mangoHud?.close()
                try {
                    wine?.let { guest ->
                        val nativeSteam = bionicSteam
                        when {
                            nativeSteam != null -> nativeSteam.stop(guest)
                            engine == WindowsEngine.FEX && containerState != null ->
                                WindowsProbe.stopGame(applicationContext, containerState, guest)
                            gameId == null -> engine.stop(guest)
                            else -> BoxRuntime.stop(guest) {
                                compositor?.let(GamescopeRuntime::stop)
                                compositor = null
                            }
                        }
                    }
                } catch (error: Exception) {
                    failure = error
                } finally {
                    try {
                        compositor?.let(GamescopeRuntime::stop)
                    } catch (error: Exception) {
                        if (failure == null) failure = error
                    }
                    gameExit?.delete()
                    dependencyProgress?.delete()
                    val sessionFailure = hostFailure ?: failure ?: if (exitCode == null && !stopRequested && !closing) {
                        IllegalStateException("Wine не вернул код завершения")
                    } else null
                    val outcome = when {
                        sessionFailure != null -> GameLaunchResult.Outcome.FAILED
                        exitCode == null && stopRequested -> GameLaunchResult.Outcome.STOPPED
                        else -> GameLaunchResult.Outcome.EXITED
                    }
                    runOnUiThread {
                        if (closing) return@runOnUiThread
                        if (gameId == null) {
                            if (sessionFailure != null) showFailure(sessionFailure) else finish()
                        } else {
                            finishGame(gameId, outcome, exitCode, sessionFailure?.localizedMessage)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun eventReceiver(): ResultReceiver? = intent.getParcelableExtra(eventsExtra)

    @Suppress("DEPRECATION")
    private fun reportGameplaySessionReady() {
        val statusReceiver = intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS) ?: return
        val channel = GameplayInputChannel(::companionInputActive)
        companionInput = channel
        channel.setActive(companionInputActive())
        statusReceiver.send(1, Bundle().apply {
            val controls = channel.companionReceiver(::requestStop, host::setMouseDisabled, host::setDirectTouch,
                ::requestRestart, ::setOutputFpsEnabled, ::setCompanionFpsLimit, ::setCompanionPerformanceHud,
                ::setMangoHudEnabled, host::setFramegenEnabled)
            putParcelable("companionControls", controls.platformReceiver())
            putString("sessionId", companionSessionId)
            putStringArrayList("capabilities",
                arrayListOf("keyboard", "disableMouse", "touchscreenMode", "restart", "outputFps", "fpsLimit", "performanceHud")
                    .apply { if (host.framegenAvailable) add("framegen2x") })
            putBoolean("mouseDisabled", false)
            putBoolean("touchscreen", true)
            putFloat("touchSensitivity", touchSensitivity)
            putBoolean("outputFps", false)
            putBoolean("performanceHud", false)
            putInt("fpsLimit", 0)
            putFramegenStatus(host.framegenStatus)
        })
    }

    @Suppress("DEPRECATION")
    private fun publishFramegenStatus(status: GamescopeHostView.FramegenStatus) {
        if (closing || stopRequested || companionInput == null) return
        intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS)?.send(6, Bundle().apply {
            putString("sessionId", companionSessionId)
            putFramegenStatus(status)
        })
    }

    private fun Bundle.putFramegenStatus(status: GamescopeHostView.FramegenStatus) {
        putBoolean("framegenRequested", status.requested)
        putBoolean("framegenActive", status.active)
        status.error?.let { putString("framegenError", it) }
    }

    @Suppress("DEPRECATION")
    private fun reportMangoHudStatus(available: Boolean, enabled: Boolean) {
        runOnUiThread {
            if (closing || stopRequested || !sessionInputReady) return@runOnUiThread
            mangoHudAvailable = available
            mangoHudShown = enabled
            publishMangoHudStatus()
            updateSessionMenuMetrics()
        }
    }

    @Suppress("DEPRECATION")
    private fun publishMangoHudStatus() {
        intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS)?.send(2, Bundle().apply {
            putString("sessionId", companionSessionId)
            putBoolean("mangoHudAvailable", mangoHudAvailable && !dualScreenControls)
            putBoolean("mangoHud", mangoHudShown && !dualScreenControls)
        })
    }

    private fun event(name: String) = Bundle().apply {
        putString(GameLaunchResult.GAME_ID, gameId)
        putString("event", name)
    }

    private fun sendEvent(name: String) = eventReceiver()?.send(0, event(name))

    private fun publishGameplayInput() {
        if (!sessionInputReady || gameId == null) return
        val events = eventReceiver() ?: return
        val channel = GameplayInputChannel(::profileInputActive)
        gameplayInput = channel
        channel.setActive(profileInputActive())
        events.send(0, event(GameplayInputClient.EVENT).apply {
            putInt(GameplayInputClient.PROTOCOL, GameplayInputClient.VERSION)
            putParcelable(GameplayInputClient.RECEIVER, channel.receiver.platformReceiver())
        })
    }

    private fun closeGameplayInput() {
        performanceHudEnabled = false
        reportOutputFps = false
        updatePerformanceHud()
        gameplayInput?.close()
        gameplayInput = null
        companionInput?.close()
        companionInput = null
        physicalGamepad?.close()
        physicalGamepad = null
    }

    @Volatile private var companionFpsLimit = 0

    private fun setCompanionFpsLimit(fps: Int) {
        if (fps == 0 || fps in 15..120) {
            companionFpsLimit = fps
            updateSessionMenuMetrics()
        }
    }

    private fun setOutputFpsEnabled(enabled: Boolean) {
        if (enabled) performanceHud?.setConfig(PerformanceHudConfig.FPS_ONLY, compactMode = false)
        performanceHudWantsFps = true
        performanceHudEnabled = enabled
        updatePerformanceHud()
    }

    private fun setCompanionPerformanceHud(settings: Bundle) {
        if (settings.containsKey(GAMEPLAY_DUAL_SCREEN)) {
            val dualScreen = settings.getBoolean(GAMEPLAY_DUAL_SCREEN)
            if (dualScreen != dualScreenControls) {
                dualScreenControls = dualScreen
                if (dualScreen) mangoHudEnabled = false
                publishMangoHudStatus()
            }
        }
        val config = PerformanceHudConfig.fromCompanion(settings)
        performanceHud?.setConfig(config, settings.getBoolean("compactMode"))
        performanceHudEnabled = settings.getBoolean("enabled")
        performanceHudWantsFps = config.showFrameRate || config.showFrameRateGraph
        reportOutputFps = settings.getBoolean("reportOutputFps")
        updatePerformanceHud()
    }

    private fun updatePerformanceHud() {
        val available = visible && sessionInputReady && !stopRequested && !closing && host.holder.surface.isValid
        val upper = available && performanceHudEnabled && !dualScreenControls
        performanceHud?.setSampling(upper)
        fpsDemand = when {
            available && dualScreenControls && reportOutputFps -> FpsDemand.LOWER
            upper && performanceHudWantsFps -> FpsDemand.UPPER
            else -> FpsDemand.NONE
        }
        updateSessionMenuMetrics()
    }

    @Suppress("DEPRECATION")
    private fun reportPerformanceFps(sample: Float?, generated: Int?) {
        intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS)?.send(3, Bundle().apply {
            putString("sessionId", companionSessionId)
            putString("outputFpsSource", if (sample != null) "gamePresent" else "unavailable")
            if (sample != null) putFloat("outputFps", sample)
            if (generated != null) putInt("generatedFps", generated)
        })
    }

    @Suppress("DEPRECATION")
    private fun toggleLocalPerformanceHud() {
        if (dualScreenControls) return
        performanceHudEnabled = !performanceHudEnabled
        updatePerformanceHud()
        intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS)?.send(4, Bundle().apply {
            putString("sessionId", companionSessionId)
            putBoolean("performanceHud", performanceHudEnabled)
        })
    }

    private fun setMangoHudEnabled(enabled: Boolean) {
        mangoHudEnabled = enabled && !dualScreenControls && mangoHudAvailable
        updateSessionMenuMetrics()
    }

    private fun updateSessionMenuMetrics() {
        sessionMenu?.setMetrics(!dualScreenControls, performanceHudEnabled, mangoHudAvailable, mangoHudEnabled,
            companionFpsLimit)
    }

    private fun runPauseControl(guest: Process, pauseInBackground: Boolean) {
        val wanted = menuOpen || (!gameplayForeground() && pauseInBackground)
        if (wanted == guestPaused || !BoxRuntime.setPaused(guest, wanted)) return
        guestPaused = wanted
        sendEvent(if (wanted) "paused" else "resumed")
        if (!wanted) runOnUiThread { updateGameInput() }
    }

    private fun updateGameInput() {
        val foreground = if (dependencyInputReady) gameplayForeground() else resumed
        val active = foreground && (sessionInputReady || dependencyInputReady) &&
            !menuOpen && !guestPaused && !stopRequested && !closing && hostFailure == null
        gameplayInput?.setActive(profileInputActive())
        companionInput?.setActive(companionInputActive())
        updatePhysicalGamepad()
        if (::host.isInitialized) {
            host.enableGameInput(active)
        }
    }

    private fun showDependencyInput() {
        runOnUiThread {
            if (dependencyInputReady || closing || stopRequested || hostFailure != null) return@runOnUiThread
            dependencyInputReady = true
            status.visibility = View.GONE
            cancelDependencyInstall.setText(R.string.buran_cancel_dependency_install)
            cancelDependencyInstall.visibility = View.VISIBLE
            host.mouse?.ready()
            updateGameInput()
        }
    }

    private fun showShaderPrewarm() {
        runOnUiThread {
            if (closing || stopRequested || hostFailure != null) return@runOnUiThread
            status.setText(R.string.buran_shader_prewarm)
            status.visibility = View.VISIBLE
            cancelDependencyInstall.setText(android.R.string.cancel)
            cancelDependencyInstall.visibility = View.VISIBLE
        }
    }

    private fun hideDependencyInput() {
        dependencyInputReady = false
        cancelDependencyInstall.visibility = View.GONE
    }

    private fun gameplayForeground() = resumed || (visible && intent.hasExtra(GAMEPLAY_SESSION_STATUS))

    private fun profileInputActive() = resumed && visibleGameInput()

    private fun companionInputActive() = visible && visibleGameInput()

    private fun visibleGameInput() = sessionInputReady && !menuOpen && !guestPaused &&
        !stopRequested && !closing && hostFailure == null && host.isShown && host.holder.surface.isValid

    override fun surfaceCreated(holder: SurfaceHolder) {
        updatePerformanceHud()
        gameplayInput?.setActive(profileInputActive())
        companionInput?.setActive(companionInputActive())
        updatePhysicalGamepad()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        performanceHud?.setSampling(false)
        fpsDemand = FpsDemand.NONE
        gameplayInput?.setActive(false)
        companionInput?.setActive(false)
        physicalGamepad?.setActive(false)
    }

    private fun setMenuOpen(open: Boolean) {
        if (!menuAvailable || stopRequested || closing || menuOpen == open) return
        menuOpen = open
        if (open) gamepadMenuShortcut.cancel()
        updateGameInput()
        sessionMenu?.display(true, open)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadMenuShortcut.handle(event, intent.getIntExtra(GAMEPLAY_QUICK_MENU_BUTTON, KeyEvent.KEYCODE_UNKNOWN))) {
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && menuAvailable && !closing && !stopRequested) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (menuOpen) setMenuOpen(false) else openQuickMenu()
            }
            return true
        }
        return forwardGameKey(event)
    }

    private fun forwardGameKey(event: KeyEvent): Boolean {
        return physicalGamepad?.key(event) == true || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        physicalGamepad?.motion(event) == true || super.dispatchGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) gamepadMenuShortcut.cancel()
        updatePhysicalGamepad()
    }

    private fun updatePhysicalGamepad() {
        physicalGamepad?.setActive(hasWindowFocus() && profileInputActive())
    }

    private fun gameBack() {
        if (menuOpen) setMenuOpen(false)
        else if (menuAvailable && intent.hasExtra(GAMEPLAY_SESSION_STATUS) && !dualScreenControls) openQuickMenu()
        else requestStop()
    }

    private fun canOpenGameplayMenu() = menuAvailable && !menuOpen && !dualScreenControls &&
        !closing && !stopRequested && resumed && hasWindowFocus() && intent.hasExtra(GAMEPLAY_SESSION_STATUS)

    @Suppress("DEPRECATION")
    private fun openQuickMenu() {
        if (canOpenGameplayMenu()) {
            val menu = intent.getParcelableExtra<PendingIntent>(GAMEPLAY_QUICK_MENU)
            if (menu != null) {
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 36) {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                } else if (Build.VERSION.SDK_INT >= 34) {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                try {
                    startIntentSenderForResult(menu.intentSender, -1, null, 0, 0, 0, options.toBundle())
                    return
                } catch (error: IntentSender.SendIntentException) {
                    Log.w("BuranGamescope", "Gameplay quick menu is unavailable", error)
                }
            }
        }
        setMenuOpen(true)
    }

    private fun failToAcquireProcess(message: String? = null) {
        val id = gameId
        if (id == null) finish()
        else finishGame(id, GameLaunchResult.Outcome.FAILED, message = message ?: "Gamescope уже используется")
    }

    private fun finishGame(id: String, outcome: GameLaunchResult.Outcome, code: Int? = null, message: String? = null) {
        if (closing) return
        closing = true
        closeGameplayInput()
        val result = GameLaunchResult(id, outcome, code, message).toIntent()
        if (restartRequested && outcome == GameLaunchResult.Outcome.STOPPED) {
            result.putExtra(GAMEPLAY_RESTART_GAME, true)
            result.putExtra("buranFramegen2x", host.framegenStatus.requested)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun onHostFailure(error: Throwable) {
        val id = gameId
        if (id == null) {
            showFailure(error)
            return
        }
        if (closing || stopRequested || hostFailure != null) return
        hostFailure = error
        closeGameplayInput()
        val worker = session
        if (worker == null) finishGame(id, GameLaunchResult.Outcome.FAILED, message = error.localizedMessage)
        else interruptSession()
    }

    private fun requestRestart() {
        if (closing || stopRequested) return
        restartRequested = true
        requestStop()
    }

    private fun requestStop() {
        val id = gameId ?: return
        if (closing || stopRequested || hostFailure != null) return
        stopRequested = true
        hideDependencyInput()
        updatePerformanceHud()
        updateGameInput()
        sessionMenu?.display(false, false)
        val worker = session
        if (worker == null) {
            finishGame(id, GameLaunchResult.Outcome.STOPPED)
        } else {
            status.text = "Завершаем игровую сессию…"
            status.visibility = View.VISIBLE
            interruptSession()
        }
    }

    private fun interruptSession() {
        synchronized(sessionSignal) {
            if (!cleaningUp) session?.interrupt()
        }
    }

    @Deprecated("Android Back callback")
    override fun onBackPressed() {
        if (gameId != null) gameBack() else super.onBackPressed()
    }

    private fun showFailure(error: Throwable) {
        runOnUiThread {
            if (!closing) {
                host.mouse?.foreground(false)
                returnToLibrary.visibility = View.VISIBLE
                returnToLibrary.requestFocus()
                status.text = error.localizedMessage ?: "Не удалось запустить Gamescope"
                status.visibility = View.VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        updatePerformanceHud()
        companionInput?.setActive(companionInputActive())
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateGameInput()
    }

    override fun onPause() {
        gamepadMenuShortcut.cancel()
        resumed = false
        updateGameInput()
        super.onPause()
    }

    override fun onStop() {
        visible = false
        updatePerformanceHud()
        companionInput?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        closing = true
        gamepadMenuShortcut.cancel()
        closeGameplayInput()
        interruptSession()
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                backCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
            }
            if (::host.isInitialized) {
                host.holder.removeCallback(this)
                host.close()
            }
        } finally {
            super.onDestroy()
            if (ownsProcess) {
                val worker = session
                val runtimeDir = if (::host.isInitialized) host.runtimeDirectory else null
                thread(name = "Buran Gamescope shutdown") {
                    worker?.join()
                    try {
                        runtimeDir?.deleteRecursively()
                    } finally {
                        // The native compositor has process lifetime, and exit releases the launch lock.
                        AndroidProcess.killProcess(AndroidProcess.myPid())
                    }
                }
            }
        }
    }

    companion object {
        private const val engineExtra = "windowsEngine"
        private const val desktopExtra = "wineDesktop"
        private const val dxvkExtra = "dxvkCheck"
        private const val gameDxvkExtra = "gameDxvk"
        private const val eventsExtra = "gameSessionEvents"
        private const val pauseInBackgroundExtra = "pauseInBackground"
        private const val monoExtra = "requiresMono"
        private const val gameArgumentsExtra = "gameArguments"
        private const val windowExecutableExtra = "windowExecutable"
        private const val externalExtra = "externalInstallation"
        private const val containerExtra = "wineContainer"
        private var processLease: FileLock? = null
        private var launchPending = false

        internal fun launchExternal(
            activity: Activity, request: ExternalWindowsLaunch, desktop: Boolean = false,
            engine: WindowsEngine = WindowsEngine.selected(activity), requiresMono: Boolean = false,
            gameDxvk: Boolean = false, events: ResultReceiver? = null, pauseInBackground: Boolean = true,
            gameArguments: List<String> = emptyList(), containerId: String? = null, standaloneMouse: Boolean = false,
            windowExecutable: String? = null,
            onLaunchRejected: (String) -> Unit = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() },
            readyToDispatch: () -> Boolean = { activity.hasWindowFocus() },
            startActivity: (Intent) -> Unit = { activity.startActivity(it) },
        ) = launch(
            activity, desktop = desktop, engine = engine, requiresMono = requiresMono, gameDxvk = gameDxvk,
            events = events, pauseInBackground = pauseInBackground, gameArguments = gameArguments, standaloneMouse = standaloneMouse,
            external = request, containerId = containerId, windowExecutable = windowExecutable,
            onLaunchRejected = onLaunchRejected, readyToDispatch = readyToDispatch, startActivity = startActivity,
        )

        internal fun launch(
            activity: Activity, game: InstalledGame? = null, desktop: Boolean = false, dxvkCheck: Boolean = false,
            engine: WindowsEngine = WindowsEngine.selected(activity), requiresMono: Boolean = false,
            gameDxvk: Boolean = false, events: ResultReceiver? = null, pauseInBackground: Boolean = true,
            gameArguments: List<String> = emptyList(), external: ExternalWindowsLaunch? = null,
            containerId: String? = null, standaloneMouse: Boolean = false,
            windowExecutable: String? = null,
            onLaunchRejected: (String) -> Unit = { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() },
            readyToDispatch: () -> Boolean = { activity.hasWindowFocus() },
            startActivity: (Intent) -> Unit = { activity.startActivity(it) },
        ) {
            require(external == null || game == null && !dxvkCheck)
            val launchGame = external?.game ?: game
            if (windowExecutable != null) {
                require(launchGame != null && !dxvkCheck && windowExecutable.length in 5..255 &&
                    windowExecutable.endsWith(".exe", ignoreCase = true) &&
                    windowExecutable.none { it.isISOControl() || it in "<>:\"/\\|?*" }
                ) { "Для ожидания игрового окна укажите имя файла .exe без пути" }
            }
            require(containerId == null || !dxvkCheck && (launchGame != null || desktop))
            val arguments = ArrayList(gameArguments)
            validateGameArguments(arguments, launchGame != null && !dxvkCheck)
            if (launchPending) {
                onLaunchRejected("Gamescope ещё завершает предыдущую сессию")
                return
            }
            val selectedEngine = if (dxvkCheck) WindowsEngine.BOX64 else engine
            if (launchGame != null && launchGame.bits != selectedEngine.bits) {
                Toast.makeText(activity, "Для ${launchGame.exe} выберите транслятор ${launchGame.bits} бит", Toast.LENGTH_LONG).show()
                return
            }
            val container = containerId?.let { Containers.find(activity, it) }
                ?: launchGame?.let { Containers.selected(activity, it.id, selectedEngine, external != null) }
            require(container == null || container.engine == selectedEngine) { "Контейнер использует другой движок" }
            val request = Intent(activity, GamescopeActivity::class.java)
                .putExtra(engineExtra, selectedEngine.name)
                .putExtra("standaloneMouse", standaloneMouse)
                .putExtra(desktopExtra, desktop)
                .putExtra(dxvkExtra, dxvkCheck)
                .putExtra(gameDxvkExtra, gameDxvk)
                .putExtra(monoExtra, requiresMono)
                .putExtra(containerExtra, container?.id)
                .putExtra(eventsExtra, events?.platformReceiver())
                .putExtra(pauseInBackgroundExtra, pauseInBackground)
                .putStringArrayListExtra(gameArgumentsExtra, arguments)
            if (windowExecutable != null) request.putExtra(windowExecutableExtra, windowExecutable)
            if (launchGame != null) request.putExtra(GameLaunchResult.GAME_ID, launchGame.id)
                .putExtra("gameExe", launchGame.exe).putExtra("gameBits", launchGame.bits)
            if (external != null) request.putExtra(externalExtra, Bundle().apply {
                putString("key", external.key)
                putString("directory", external.directory.path)
                putString("cwd", external.relativeWorkingDirectory.ifEmpty { "." })
                external.steamAppId?.let { putInt(GAMEPLAY_STEAM_APP_ID, it) }
                external.steamRemoteDirectory?.let { putString("steamRemoteDirectory", it.path) }
                external.steamUserDirectory?.let { putString("steamUserDirectory", it.path) }
                putStringArrayList("env", ArrayList(external.environment.map { (name, value) -> "$name=$value" }))
            })
            fun dispatch() = startWhenReady(activity, request, onLaunchRejected, readyToDispatch, startActivity)
            if (container != null) Containers.prepare(activity, container, ::dispatch) else dispatch()
        }

        private fun startWhenReady(
            activity: Activity, request: Intent, onLaunchRejected: (String) -> Unit,
            readyToDispatch: () -> Boolean, startActivity: (Intent) -> Unit,
        ) {
            if (launchPending) {
                onLaunchRejected("Gamescope ещё завершает предыдущую сессию")
                return
            }
            if (canStart(activity)) {
                startActivity(request)
                return
            }
            launchPending = true
            val context = activity.applicationContext
            thread(name = "Buran Gamescope launch") {
                val deadline = SystemClock.elapsedRealtime() + 12_000L
                var ready: Boolean
                do {
                    Thread.sleep(100)
                    ready = canStart(context)
                } while (!ready && SystemClock.elapsedRealtime() < deadline)
                activity.runOnUiThread {
                    launchPending = false
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        if (ready && readyToDispatch()) startActivity(request)
                        else onLaunchRejected("Gamescope ещё завершает предыдущую сессию")
                    }
                }
            }
        }

        private fun canStart(context: Context): Boolean = lock(context)?.let { lease ->
            lease.channel().close()
            true
        } ?: false

        private fun lock(context: Context): FileLock? {
            val channel = FileChannel.open(context.buranFilesDir.resolve(".gamescope.lock").toPath(), CREATE, WRITE)
            try {
                channel.tryLock()?.let { return it }
            } catch (_: OverlappingFileLockException) {
                // The process already has its Gamescope activity.
            } catch (error: Exception) {
                channel.close()
                throw error
            }
            channel.close()
            return null
        }
    }
}

// Send the platform Parcelable even when the callback is implemented by a client-only class.
private fun ResultReceiver.platformReceiver(): ResultReceiver {
    val parcel = Parcel.obtain()
    return try {
        writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        ResultReceiver.CREATOR.createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}
