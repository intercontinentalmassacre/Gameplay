package com.winlator.xenvironment.components

import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.gamenative.BuildConfig
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.display.GamescopePayload
import com.winlator.contents.AdrenotoolsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.widget.XServerView
import com.winlator.xenvironment.ImageFs
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GamescopeProcessAndroidTest {
    @Test
    fun launchesXwaylandAndPresentsGuestFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val payload = runBlocking { GamescopePayload.ensure(context) }
        val imageFs = ImageFs.find(context)
        val tmp = Files.createTempDirectory(context.cacheDir.toPath(), "gs-").toFile()
        try {
            val installedPreload = File(imageFs.libDir, BuildConfig.PRELOAD_BIONIC_SO)
            val preload = if (installedPreload.isFile || !BuildConfig.MODERN_ANDROID) {
                installedPreload
            } else {
                File(tmp, BuildConfig.PRELOAD_BIONIC_SO).apply {
                    context.assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
                }
            }
            assertTrue("Initialize the Bionic container image before this test: $preload", preload.isFile)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var dialog: Dialog? = null
                var view: XServerView? = null
                var compositor: Process? = null
                try {
                    scenario.onActivity { activity ->
                        assertNull("Run this test with no game session", PluviaApp.xEnvironment)
                        val gamescopeView = XServerView(activity, XServer(ScreenInfo(640, 360), false), "vulkan").apply {
                            isGamescopeMode = true
                        }
                        view = gamescopeView
                        dialog = Dialog(activity).apply {
                            setContentView(gamescopeView)
                            show()
                            window?.setLayout(640, 360)
                        }
                    }
                    assertTrue("Gamescope DRM daemon did not start", checkNotNull(view).awaitGamescopeSurface())
                    val env = smokeEnvironment(context, imageFs, preload, tmp)
                    val driver = InstrumentationRegistry.getArguments().getString("gamescopeDriver")
                    if (!driver.isNullOrBlank()) {
                        AdrenotoolsManager(context).setDriverById(env, imageFs, driver)
                        assertTrue("Could not stage Turnip driver $driver", env.has("ADRENOTOOLS_DRIVER_PATH"))
                    }
                    val compositorLog = File(tmp, "gamescope.log")
                    compositor = startProcess(
                        env, tmp, compositorLog, File(payload, "bin/gamescope").absolutePath,
                        "--backend", "drm", "--expose-wayland", "-W", "640", "-H", "360",
                        "-w", "640", "-h", "360", "--", File(payload, "bin/gn-wsi-test").absolutePath,
                    )

                    val socket = File(env.get("GAMESCOPE_X11_DISPLAY"))
                    val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(20)
                    val x11Log = File(tmp, "xwayland.log")
                    var connected = false
                    while (compositor.isAlive && SystemClock.elapsedRealtime() < deadline) {
                        if (socket.exists()) {
                            val client = startProcess(
                                env, tmp, x11Log, File(payload, "bin/gn-xtree-probe").absolutePath, socket.absolutePath,
                            )
                            try {
                                connected = client.waitFor(2, TimeUnit.SECONDS) &&
                                    client.exitValue() == 0 && x11Log.readText().contains("root=0x")
                            } finally {
                                client.destroyForcibly()
                            }
                            if (connected) break
                        }
                        Thread.sleep(200)
                    }
                    assertTrue("XWayland never answered\n${compositorLog.readText()}\n${x11Log.takeIf { it.isFile }?.readText().orEmpty()}", connected)
                    assertTrue("Compositor timed out\n${compositorLog.readText()}", compositor.waitFor(35, TimeUnit.SECONDS))
                    val output = compositorLog.readText()
                    assertEquals(output, 0, compositor.exitValue())
                    assertTrue(output, output.contains("frame 11 presented") && output.contains("done - all frames presented"))
                } finally {
                    compositor?.let { process ->
                        process.destroy()
                        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                    }
                    scenario.onActivity {
                        view?.stopGamescope()
                        dialog?.dismiss()
                    }
                }
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun smokeEnvironment(context: Context, imageFs: ImageFs, preload: File, tmp: File): EnvVars =
        EnvVars().apply {
            put("HOME", tmp.absolutePath)
            put("TMPDIR", tmp.absolutePath)
            put("PREFIX", File(imageFs.rootDir, "usr").absolutePath)
            put("PATH", "/system/bin")
            put("LD_LIBRARY_PATH", "${imageFs.libDir}:/system/lib64")
            put("LD_PRELOAD", preload.absolutePath)
            BionicProgramLauncherComponent.applyGamescopeEnv(context, this, tmp, null)
            put("GNDRM_SCANOUT", "640x360")
            put("GNDRM_SCANOUT_NESTED", "640x360")
        }

    private fun startProcess(env: EnvVars, dir: File, log: File, vararg arguments: String): Process =
        ProcessBuilder(buildList {
            if (BuildConfig.MODERN_ANDROID) add("/system/bin/linker64")
            addAll(arguments)
        }).directory(dir).redirectErrorStream(true).redirectOutput(log).apply {
            environment().clear()
            for (name in env) environment()[name] = env.get(name)
        }.start()
}
