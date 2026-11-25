package app.gamenative.display

import android.app.Dialog
import android.system.Os
import android.view.SurfaceHolder
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import com.winlator.widget.XServerView
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GamescopeSurfaceAndroidTest {
    @Test
    fun gamescopeRetargetsRecreatedSurfaceAndStaysStopped() {
        val callbacks = LinkedBlockingQueue<Boolean>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var dialog: Dialog? = null
            var viewToStop: XServerView? = null
            lateinit var gamescopeView: XServerView
            lateinit var host: FrameLayout
            try {
                scenario.onActivity { activity ->
                    assertNull("Run this test with no game session", PluviaApp.xEnvironment)
                    gamescopeView = XServerView(activity, XServer(ScreenInfo(640, 360), false), "vulkan").apply {
                        isGamescopeMode = true
                        setScanoutLayoutOverride(Os.getenv("GNDRM_SCANOUT_LAYOUT"))
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = Unit
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                callbacks.offer(true)
                            }
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                callbacks.offer(false)
                            }
                        })
                    }
                    viewToStop = gamescopeView
                    host = FrameLayout(activity).apply { addView(gamescopeView) }
                    dialog = Dialog(activity).apply {
                        setContentView(host)
                        show()
                        window?.setLayout(640, 360)
                    }
                }

                fun awaitSurface(created: Boolean) {
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (true) {
                        val event = callbacks.poll((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                        check(event != null) { "Surface callback missing (created=$created)" }
                        if (event == created) return
                    }
                }

                fun assertRetarget(expected: Int) {
                    scenario.onActivity {
                        val holder = gamescopeView.holder
                        val frame = holder.surfaceFrame
                        assertTrue(holder.surface.isValid)
                        assertEquals(expected, GameNativeDrm.nativeRetargetSurface(holder.surface, frame.width(), frame.height()))
                    }
                }

                awaitSurface(true)
                assertTrue(gamescopeView.awaitGamescopeSurface())
                assertRetarget(0)
                scenario.onActivity { host.removeView(gamescopeView) }
                awaitSurface(false)
                scenario.onActivity { host.addView(gamescopeView) }
                awaitSurface(true)
                assertRetarget(0)

                scenario.onActivity {
                    gamescopeView.stopGamescope()
                    gamescopeView.stopGamescope()
                    host.removeView(gamescopeView)
                }
                awaitSurface(false)
                scenario.onActivity { host.addView(gamescopeView) }
                awaitSurface(true)
                assertFalse(gamescopeView.awaitGamescopeSurface())
                assertRetarget(GameNativeDrm.E_NO_SESSION)
            } finally {
                scenario.onActivity {
                    viewToStop?.stopGamescope()
                    dialog?.dismiss()
                }
            }
        }
    }
}
