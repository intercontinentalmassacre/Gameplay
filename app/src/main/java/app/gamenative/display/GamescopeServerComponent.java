package app.gamenative.display;

import com.winlator.container.Container;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xenvironment.ImageFs;
import com.winlator.xserver.XServer;

import java.io.File;

/**
 * The gamescope session's app-side endpoint: starts the wayland injector and drives the
 * command queue's applicability.
 */
public class GamescopeServerComponent extends EnvironmentComponent {

    /** How long to wait for gamescope to answer before giving up on the start re-apply. */
    private static final int START_POLL_TRIES = 120;
    private static final long START_POLL_MS = 250;

    private final File xdgRunDir;
    private final int sessionId;
    private final XServer xServer;
    private volatile boolean stopped;
    private boolean ready;
    private boolean paused;

    public GamescopeServerComponent(File payload, ImageFs imageFs, Container container, XServer xServer) {
        // Gamescope mode: no winlator X11 server (gamescope spawns its own XWayland); start the wayland
        // injector, stopped in exit().
        sessionId = GamescopeCommandQueue.beginSession(
            payload,
            GamescopeSettings.x11DisplayPath(imageFs.getRootDir()),
            GamescopeSettings.fromContainer(container)
        );

        xdgRunDir = new File(imageFs.getRootDir().getPath(), "tmp/xdg-run");
        this.xServer = xServer;
    }

    public XServer getXServer() {
        return xServer;
    }

    @Override
    public void start() {
        int rc = GameNativeDrm.nativeInjectorStart(xdgRunDir.getAbsolutePath(), "gamescope-0");
        if (rc != 0) {
            throw new IllegalStateException("Gamescope input injector could not start (" + rc + ")");
        }
        // The injector connects to gamescope's wayland socket a moment after it starts; a command sent
        // before then is dropped.
        Thread waiter = new Thread(this::awaitConnected, "gamescope-reapply");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void awaitConnected() {
        for (int i = 0; i < START_POLL_TRIES && !stopped; i++) {
            if (gamescopeAnswered()) {
                synchronized (this) {
                    if (stopped) return;
                    ready = true;
                    GamescopeCommandQueue.setApplicable(sessionId, !paused);
                }
                return;
            }
            try {
                Thread.sleep(START_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Whether gamescope has answered display_info (connector or features present). */
    private static boolean gamescopeAnswered() {
        String info = GameNativeDrm.nativeDisplayInfo();
        if (info == null || info.isEmpty()) return false;
        for (String field : info.split(";")) {
            int eq = field.indexOf('=');
            if (eq < 0) continue;
            String key = field.substring(0, eq);
            String value = field.substring(eq + 1);
            if (key.equals("features") && !value.equals("0x0")) return true;
            if (key.equals("connector") && !value.isEmpty()) return true;
        }
        return false;
    }

    @Override
    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        GamescopeCommandQueue.reset();
        try {
            GameNativeDrm.nativeInjectorStop();
        } finally {
            GameNativeDrm.nativeStop();
        }
    }

    public synchronized void pause() {
        if (stopped) return;
        paused = true;
        // Not applicable while gamescope is frozen: setters only update the
        // desired state, and the resume re-apply sends the whole set.
        GamescopeCommandQueue.setApplicable(sessionId, false);
        GameNativeDrm.nativeInjectorSetSuspended(true);
    }

    public synchronized void resume() {
        if (stopped) return;
        paused = false;
        // Injector may touch the socket again: mark applicable, which re-applies
        // the full desired state so a command lost to the freeze cannot stick.
        GameNativeDrm.nativeInjectorSetSuspended(false);
        GamescopeCommandQueue.setApplicable(sessionId, ready);
    }
}
