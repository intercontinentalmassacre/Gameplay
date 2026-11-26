package com.winlator.core;

import com.winlator.container.Container;
import com.winlator.core.envvars.EnvVars;
import com.winlator.winhandler.OnGetProcessInfoListener;
import com.winlator.winhandler.ProcessInfo;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.XServer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/* loaded from: classes.dex */
public class Win32AppWorkarounds {
    private final XServer xServer;
    private volatile short taskAffinityMask;
    private volatile short taskAffinityMaskWoW64;

    private interface DXWrapperConfigWorkaround extends Workaround {
        void setValue(String str, KeyValueSet keyValueSet);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface DXWrapperWorkaround extends Workaround {
        String getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface EnvVarsWorkaround extends Workaround {
        void apply(EnvVars envVars);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface GraphicsDriverWorkaround extends Workaround {
        String getValue();
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface ScreenSizeWorkaround extends Workaround {
        String getValue();
    }

    private interface WinComponentsWorkaround extends Workaround {
        void setValue(KeyValueSet keyValueSet);
    }

    /* JADX INFO: Access modifiers changed from: private */
    interface WindowWorkaround extends Workaround {
        void apply(Window window);
    }

    private interface Workaround {
    }

    private static class MultiWorkaround implements Workaround {
        private final Workaround[] list;

        public MultiWorkaround(Workaround... list) {
            this.list = list;
        }
    }

    public Win32AppWorkarounds(XServer xServer) {
        this.xServer = xServer;
    }

    public void setTaskAffinityMasks(int taskAffinityMask, int taskAffinityMaskWoW64) {
        this.taskAffinityMask = (short) taskAffinityMask;
        this.taskAffinityMaskWoW64 = (short) taskAffinityMaskWoW64;
    }

    private void applyWorkaround(Workaround workaround) {
        // Note: Most workarounds that require activity-level changes (env vars, screen size, etc.)
        // are now handled at the container/environment setup level in XServerScreen.kt
        // This class now focuses primarily on window-level workarounds
        if (workaround instanceof WindowWorkaround) {
            // Window workarounds can still be applied here
            // They will be applied in applyWindowWorkarounds when the window is created
        }
        // Other workaround types (EnvVars, ScreenSize, DXWrapper, etc.) are now handled
        // during environment setup phase rather than at window creation time
    }

    public void applyStartupWorkarounds(String className) {
        Workaround workaround = getWorkaroundFor(className);
        if (workaround == null) {
            return;
        }
        if (workaround instanceof MultiWorkaround) {
            for (Workaround workaround2 : ((MultiWorkaround) workaround).list) {
                applyWorkaround(workaround2);
            }
            return;
        }
        applyWorkaround(workaround);
    }

    private static final long PROCESS_SNAPSHOT_TIMEOUT_MS = 2000;

    /** Lazily created so loading this class for the pure mask decision starts no thread. */
    private static final class FallbackExecutorHolder {
        private static final ExecutorService INSTANCE = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "win32-window-affinity");
            thread.setDaemon(true);
            return thread;
        });
    }

    private void sendProcessAffinity(Window window, int processAffinity) {
        int processId = window.getProcessId();
        String className = window.getClassName();
        WinHandler winHandler = this.xServer.getWinHandler();
        if (winHandler == null) return;
        if (processId > 0) {
            winHandler.setProcessAffinity(processId, processAffinity);
        } else if (!className.isEmpty()) {
            winHandler.setProcessAffinity(className, processAffinity);
        }
    }

    /** The default 32-bit CPU list as the 16-bit mask the task affinity fields hold. */
    private static int defaultWoW64Mask() {
        return (short) ProcessHelper.getAffinityMask(Container.getFallbackCPUListWoW64());
    }

    /** The 32-bit list is only explicit when it was set to something other than the default. */
    static boolean isExplicitWoW64Mask(int maskWoW64, int defaultMaskWoW64) {
        return maskWoW64 != 0 && maskWoW64 != defaultMaskWoW64;
    }

    /**
     * The mask a window's process gets once the winhandler reports whether it is WoW64. The
     * 32-bit list wins only for a 32-bit process and only when that list was set explicitly;
     * otherwise the main list is used. 0 means "send nothing".
     */
    static int resolveWindowAffinityMask(int taskAffinityMask, int taskAffinityMaskWoW64,
                                         int defaultMaskWoW64, boolean processIsWoW64) {
        boolean wow64 = processIsWoW64 && isExplicitWoW64Mask(taskAffinityMaskWoW64, defaultMaskWoW64);
        return wow64 ? taskAffinityMaskWoW64 : taskAffinityMask;
    }

    /**
     * Proton 10 and later never set _NET_WM_WOW64, so the window cannot say whether its process
     * is 32-bit. The winhandler's process list decides instead. Without an answer the process
     * gets the main list, as it did before the property existed.
     */
    private void applyProcessAffinityWithoutWoW64Property(Window window) {
        final int processId = window.getProcessId();
        final String className = window.getClassName();
        final int mainMask = this.taskAffinityMask;
        final int wow64Mask = this.taskAffinityMaskWoW64;
        final int defaultWoW64 = defaultWoW64Mask();

        if (processId <= 0) {
            // No PID to look up: behaviour as before, the main list by class name.
            if (mainMask != 0) sendProcessAffinity(window, mainMask);
            return;
        }
        if (mainMask == wow64Mask || !isExplicitWoW64Mask(wow64Mask, defaultWoW64)) {
            // Equal or non-explicit lists need no process lookup: one send, as before.
            if (mainMask != 0) sendProcessAffinity(window, mainMask);
            return;
        }
        final WinHandler winHandler = this.xServer.getWinHandler();
        if (winHandler == null) return;

        FallbackExecutorHolder.INSTANCE.execute(() -> {
            List<ProcessInfo> processes = snapshotProcesses(winHandler);
            ProcessInfo info = null;
            if (processes != null) {
                for (ProcessInfo candidate : processes) {
                    if (candidate.pid == processId) {
                        info = candidate;
                        break;
                    }
                }
            }
            int target = resolveWindowAffinityMask(mainMask, wow64Mask, defaultWoW64,
                info != null && info.wow64Process);
            if (target == 0) return;
            winHandler.setProcessAffinity(processId, target);
            Timber.i(
                "Applied mask 0x%s to %s (PID %d, wow64=%s)",
                Integer.toHexString(target), className, processId,
                info != null ? String.valueOf(info.wow64Process) : "unknown");
        });
    }

    /**
     * One LIST_PROCESSES round trip, or null when the reply did not arrive in time or another
     * listener is installed. The exit watcher owns the winhandler's single process-info listener
     * while it runs, so this never replaces it and only restores its own listener when that one
     * is still installed.
     */
    private static List<ProcessInfo> snapshotProcesses(WinHandler winHandler) {
        final OnGetProcessInfoListener previous = winHandler.getOnGetProcessInfoListener();
        if (previous != null) return null;

        final List<ProcessInfo> processes = new ArrayList<>();
        final CountDownLatch done = new CountDownLatch(1);
        final OnGetProcessInfoListener collector = (index, count, processInfo) -> {
            synchronized (processes) {
                if (index == 0) processes.clear();
                if (processInfo != null) processes.add(processInfo);
                if (count == 0 || processes.size() >= count) done.countDown();
            }
        };

        winHandler.setOnGetProcessInfoListener(collector);
        try {
            if (winHandler.getOnGetProcessInfoListener() != collector) return null;
            winHandler.listProcesses();
            if (!done.await(PROCESS_SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
            synchronized (processes) {
                return new ArrayList<>(processes);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (winHandler.getOnGetProcessInfoListener() == collector) {
                winHandler.setOnGetProcessInfoListener(previous);
            }
        }
    }

    public void applyWindowWorkarounds(Window window) {
        Workaround workaround = getWorkaroundFor(window.getClassName());
        boolean canApplyProcessAffinity = false;
        if (workaround instanceof WindowWorkaround) {
            ((WindowWorkaround) workaround).apply(window);
        } else if (workaround instanceof MultiWorkaround) {
            Workaround[] workaroundArr = ((MultiWorkaround) workaround).list;
            int length = workaroundArr.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    break;
                }
                Workaround workaround2 = workaroundArr[i];
                if (!(workaround2 instanceof WindowWorkaround)) {
                    i++;
                } else {
                    ((WindowWorkaround) workaround2).apply(window);
                    break;
                }
            }
        }
        int windowGroup = window.getWMHintsValue(Window.WMHints.WINDOW_GROUP);
        if (window.isRenderable() && !window.getClassName().isEmpty() && windowGroup == window.id) {
            canApplyProcessAffinity = true;
        }
        if (!canApplyProcessAffinity) return;
        if (window.getClassName().equals("steam.exe")) {
            Timber.i("Steam.exe found, not applying affinity!");
            return;
        }
        if (this.taskAffinityMask == 0 && this.taskAffinityMaskWoW64 == 0) return;

        if (window.hasWoW64Property()) {
            int processAffinity = window.isWoW64() ? this.taskAffinityMaskWoW64 : this.taskAffinityMask;
            if (processAffinity != 0) sendProcessAffinity(window, processAffinity);
            return;
        }
        applyProcessAffinityWithoutWoW64Property(window);
    }

    private Workaround getWorkaroundFor(String className) {
        switch (className.toLowerCase(Locale.ENGLISH)) {
            default:
                return null;
        }
    }
}
