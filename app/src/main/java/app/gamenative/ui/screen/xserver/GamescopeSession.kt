package app.gamenative.ui.screen.xserver

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.display.GameNativeDrm
import app.gamenative.display.GamescopeInputSink
import app.gamenative.display.GamescopePayload
import app.gamenative.display.GamescopeRuntime
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.PreLaunchSetup
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVars
import com.winlator.widget.XServerView
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.GuestProgramLauncherComponent
import com.winlator.xserver.XServer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal fun hasContainerVariantChanged(container: Container, imageFs: ImageFs): Boolean {
    val variant = container.getExtra("appliedContainerVariant")
    val wine = container.getExtra("appliedWineVersion")
    return container.getExtra("imgVersion") != imageFs.getVersion().toString() ||
        (variant.isNotEmpty() && wine.isNotEmpty() && (container.containerVariant != variant || container.wineVersion != wine))
}

internal fun shouldLaunchGamescope(context: Context, container: Container, appId: String, forceX11: Boolean): Boolean {
    if (forceX11 || !Container.isGamescopeSupported(Build.VERSION.SDK_INT) ||
        Container.isX11Display(container.containerVariant, container.displayServer)
    ) return false
    return !PreLaunchSetup.hasPendingSteps(
        container, appId, ContainerUtils.extractGameSourceFromContainerId(appId),
        hasContainerVariantChanged(container, ImageFs.find(context)),
    )
}

internal fun prepareGamescopePayload(context: Context, container: Container): GamescopeRuntime {
    val requested = container.getExtra(GamescopePayload.EXPERIMENTAL_CONTAINER_EXTRA, "false").toBoolean()
    if (requested) {
        val experimental = try {
            runBlocking { GamescopePayload.ensureExperimental(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Experimental Gamescope GL bundle unavailable")
            null
        }
        if (experimental != null) return GamescopeRuntime(experimental)
    }
    fun setSplashText(text: String) {
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(text))
    }
    if (!GamescopePayload.isReady(context.filesDir)) {
        setSplashText(context.getString(R.string.gamescope_preparing))
        runBlocking {
            var lastPercent = -1
            GamescopePayload.ensure(context) { progress ->
                val percent = (progress * 100).roundToInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    setSplashText(context.getString(R.string.gamescope_download_progress, percent))
                }
            }
        }
        setSplashText(context.getString(R.string.gamescope_starting))
    }
    val capIcd = if (requested) try {
        runBlocking { GamescopePayload.ensureExperimentalCap(context) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Experimental Gamescope cap shim unavailable. Starting stable Gamescope.")
        null
    } else null
    return GamescopeRuntime(GamescopePayload.directory(context.filesDir), capIcd)
}

internal fun createGamescopeView(context: Context, xServer: XServer, container: Container): XServerView {
    xServer.setInputEventSink(GamescopeInputSink(xServer.screenInfo))
    return XServerView(context, xServer, "vulkan").apply {
        isGamescopeMode = true
        setGamescopeContainer(container)
        setScanoutLayoutOverride(EnvVars(container.envVars).get("GNDRM_SCANOUT_LAYOUT"))
    }
}

@Composable
internal fun TrackGamescopeRefreshRate(view: View?) {
    DisposableEffect(view) {
        val manager = view?.context?.getSystemService(DisplayManager::class.java)
        fun update() { (view as? XServerView)?.updateGamescopePanelRefreshRate() }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) = update()
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        update()
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
}

// Some games create an XWayland window before Wine has assigned a foreground window.
internal fun armGamescopeWindow(runtime: File, environment: XEnvironment, launcher: GuestProgramLauncherComponent) {
    if (launcher !is BionicProgramLauncherComponent) return
    val helper = File(runtime, "bin/gn-activate.exe")
    if (!helper.isFile) return
    val path = "Z:" + helper.absolutePath.replace('/', '\\')
    val launches = AtomicInteger()
    launcher.setOnGuestStarted {
        val launch = launches.incrementAndGet()
        Thread({
            runCatching {
                val deadline = SystemClock.elapsedRealtime() + 120_000L
                // The helper lacks the controller shim. Never let it boot the prefix.
                while (!ProcessHelper.isWineServerRunning()) {
                    if (launches.get() != launch || !launcher.isGuestRunning || SystemClock.elapsedRealtime() >= deadline) return@Thread
                    Thread.sleep(250)
                }
                Thread.sleep(8_000)
                if (launches.get() == launch && launcher.isGuestRunning &&
                    PluviaApp.xEnvironment === environment && ProcessHelper.isWineServerRunning()
                ) {
                    launcher.execGamescopeShellCommand("wine \"$path\" --watch")
                }
            }
                .onFailure { Timber.w(it, "Could not activate the Gamescope window") }
        }, "gamescope-window-activation").apply { isDaemon = true }.start()
    }
}
