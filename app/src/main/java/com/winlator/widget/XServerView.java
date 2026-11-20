package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.container.Container;
import com.winlator.renderer.ASurfaceRenderer;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.gamenative.display.GameNativeDrm;
import app.gamenative.display.GamescopeCommandQueue;
import app.gamenative.display.GamescopeSettings;
import timber.log.Timber;

@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private XServerRenderer renderer;
    private final XServer xServer;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private int frameRateLimit = 0;

    public XServerView(Context context, XServer xServer, String selectedRenderer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        this.xServer = xServer;
        if (selectedRenderer.equalsIgnoreCase("vulkan")) {
            Drawable.DRAWABLE_ASR_MODE(false);
            renderer = new VulkanRenderer(this, xServer);
        } else if (selectedRenderer.equalsIgnoreCase("surfaceflinger")) {
            Drawable.DRAWABLE_ASR_MODE(true);
            renderer = new ASurfaceRenderer(this, xServer);
        }
    }

    public XServer getxServer() {
        return xServer;
    }

    /** Gamescope mode: the gndrm daemon consumes this view's Surface (two producers are invalid), so surface callbacks drive GameNativeDrm's JNI lifecycle, not the X11 renderer. */
    private boolean gamescopeMode;
    private boolean gamescopeStopped;
    private Container gamescopeContainer;
    private volatile Display gamescopeStartupDisplay;
    private int gamescopePanelHz;
    private volatile boolean gamescopeSurfaceReady;
    private final CountDownLatch gamescopeStartup = new CountDownLatch(1);

    public void setGamescopeMode(boolean gamescopeMode) {
        this.gamescopeMode = gamescopeMode;
    }

    public boolean isGamescopeMode() {
        return gamescopeMode;
    }

    public void setGamescopeContainer(Container container) {
        gamescopeContainer = container;
    }

    public Display getGamescopeStartupDisplay() {
        return gamescopeStartupDisplay;
    }

    private String scanoutLayoutOverride;

    public void setScanoutLayoutOverride(String scanoutLayoutOverride) {
        this.scanoutLayoutOverride = scanoutLayoutOverride;
    }

    /** Wait on the launch worker for the Android Surface to be adopted before starting the guest. */
    public boolean awaitGamescopeSurface() throws InterruptedException {
        return gamescopeStartup.await(10, TimeUnit.SECONDS) && gamescopeSurfaceReady;
    }

    public synchronized void stopGamescope() {
        if (!gamescopeMode || gamescopeStopped) return;
        gamescopeStopped = true;
        gamescopeSurfaceReady = false;
        gamescopeStartup.countDown();
        xServer.setInputEventSink(null);
        GamescopeCommandQueue.reset();
        GameNativeDrm.endSurfaceSession();
        GameNativeDrm.nativeStop();
    }

    /** Re-read the mode of the display hosting this view, including after moving it to a Presentation. */
    public synchronized void updateGamescopePanelRefreshRate() {
        if (!gamescopeMode || gamescopeStopped) return;
        Display display = getDisplay();
        int panelHz = display != null ? Math.round(display.getRefreshRate()) : 0;
        if (panelHz <= 0 || panelHz == gamescopePanelHz) return;
        GameNativeDrm.nativeSetPanelRefreshHz(panelHz);
        if (gamescopePanelHz > 0) GamescopeCommandQueue.INSTANCE.nudgeDisplayMode();
        gamescopePanelHz = panelHz;
    }

    private synchronized void retargetGamescope(SurfaceHolder holder, int width, int height) {
        if (gamescopeStopped || width <= 0 || height <= 0 || !holder.getSurface().isValid()) return;
        updateGamescopePanelRefreshRate();
        int result = GameNativeDrm.nativeRetargetSurface(holder.getSurface(), width, height);
        if (result == GameNativeDrm.E_NO_SESSION) {
            gamescopeStartupDisplay = getDisplay();
            if (gamescopeContainer != null) {
                GamescopeSettings.configureDisplayForDaemon(gamescopeContainer, gamescopeStartupDisplay);
            }
            try {
                if (scanoutLayoutOverride != null && !scanoutLayoutOverride.isEmpty()) {
                    Os.setenv("GNDRM_SCANOUT_LAYOUT", scanoutLayoutOverride, true);
                } else {
                    Os.unsetenv("GNDRM_SCANOUT_LAYOUT");
                }
            } catch (ErrnoException e) {
                Timber.w(e, "Could not set GNDRM_SCANOUT_LAYOUT");
            }
            GameNativeDrm.beginSurfaceSession();
            result = GameNativeDrm.nativeStart("gndrm", holder.getSurface(), width, height);
        }
        if (result == 0) {
            gamescopeSurfaceReady = true;
            gamescopeStartup.countDown();
        } else {
            Timber.e("Gamescope Surface setup failed (rc=%d, %dx%d)", result, width, height);
        }
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (isGamescopeMode()) {
            retargetGamescope(holder, holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }
        if (renderer instanceof ASurfaceRenderer scRenderer) {
            scRenderer.onSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (isGamescopeMode()) {
            // Forward the real geometry: the daemon re-adopts the window (a size change on the same window is
            // a cheap geometry update) and keeps the retained frame on the layer.
            retargetGamescope(holder, width, height);
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (isGamescopeMode()) {
            // A display power cycle destroys this Surface but must NOT end the session: the daemon, its
            // sockets and gamescope's connection stay alive, and the next surfaceCreated retargets the new
            // window.
            return;
        }
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceDestroyed();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceDestroyed();
        }
    }

    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    public void onPause() {}
    public void onResume() {}

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.setFpsLimit(this.frameRateLimit);
        }
    }

    public void requestRender() {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.queueSceneUpdate();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.updateScene();
        }
    }
}
