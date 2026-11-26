package com.winlator.xenvironment.components;

import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.Image;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;

import com.winlator.PrefManager;

import app.gamenative.display.GameNativeDrm;
import app.gamenative.display.GamescopePayload;
import app.gamenative.display.GamescopeSettings;
import app.gamenative.shadercache.FossilizeManager;
import app.gamenative.utils.LsfgQuickMenuHelper;
import app.gamenative.utils.LsfgVkManager;
import com.winlator.box86_64.Box86_64Preset;
import com.winlator.box86_64.Box86_64PresetManager;
import com.winlator.container.Container;
import com.winlator.container.ContainerOverlay;
import com.winlator.container.Shortcut;
import com.winlator.contents.AdrenotoolsManager;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.DefaultVersion;
import com.winlator.core.envvars.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.ProcessHelper;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.WineInfo;
import com.winlator.fexcore.FEXCoreManager;
import com.winlator.fexcore.FEXCorePreset;
import com.winlator.fexcore.FEXCorePresetManager;
import com.winlator.sysvshm.SysVSHMConnectionHandler;
import com.winlator.sysvshm.SysVSHMRequestHandler;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.ImageFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import app.gamenative.BuildConfig;
import app.gamenative.PluviaApp;
import app.gamenative.events.AndroidEvent;
import app.gamenative.fexcache.FexCodeCache;
import app.gamenative.service.SteamService;

public class BionicProgramLauncherComponent extends GuestProgramLauncherComponent {
    private static final String VKSHIM_SYSTEM_DRIVER = "GN_VKSHIM_SYSTEM_DRIVER";
    private String guestExecutable;
    // Session-scoped: one launcher instance owns one guest process. The static
    // shared PID let one session kill another's process (see production plan).
    private int pid = -1;
    private String sessionId = java.util.UUID.randomUUID().toString();
    private String[] bindingPaths;
    private EnvVars envVars;
    private WineInfo wineInfo;
    private String box64Version = DefaultVersion.BOX64;
    private String box64Preset = Box86_64Preset.COMPATIBILITY;
    private String fexcorePreset = FEXCorePreset.INTERMEDIATE;
    private Callback<Integer> terminationCallback;
    private static final Object lock = new Object();
    private boolean wow64Mode = true;
    private final ContentsManager contentsManager;
    private final ContentProfile wineProfile;
    private Container container;
    private File workingDir;
    private boolean gamescopeMode;
    private boolean toolMode;
    private volatile EnvVars gamescopeGuestEnv;
    private Display gamescopeDisplay;
    private File gamescopeRuntime;
    private File gamescopeIcd;

    public void setGamescopeRuntime(File runtime, File icd) {
        gamescopeRuntime = runtime;
        gamescopeIcd = icd;
    }

    public void setGamescopeDisplay(Display display) {
        gamescopeDisplay = display;
    }

    public void setGamescopeModeOverride(Boolean override) {
        gamescopeMode = Boolean.TRUE.equals(override);
    }

    public void setToolMode(boolean toolMode) {
        this.toolMode = toolMode;
    }

    public void setWineInfo(WineInfo wineInfo) {
        this.wineInfo = wineInfo;
    }
    public WineInfo getWineInfo() {
        return this.wineInfo;
    }

    public Container getContainer() { return this.container; }
    public void setContainer(Container container) {
        this.container = container;
        // A process killed before teardown may leave the previous session's config behind.
        if (container != null) FEXCoreManager.deleteContainerConfig(container.getRootDir());
    }

    /** Numeric Steam appid for the game in this container (e.g. "221380").
     *  Set from XServerScreen before start(); only consumed in real-Steam mode
     *  to publish SteamGameId / SteamAppId for the steam_helper handshake. */
    private String steamAppId;
    public void setSteamAppId(String steamAppId) { this.steamAppId = steamAppId; }

    public BionicProgramLauncherComponent(ContentsManager contentsManager, ContentProfile wineProfile) {
        this.contentsManager = contentsManager;
        this.wineProfile = wineProfile;
    }

    private Runnable preUnpack;
    public void setPreUnpack(Runnable r) { this.preUnpack = r; }
    private Runnable onGuestStarted;
    public void setOnGuestStarted(Runnable callback) { onGuestStarted = callback; }
    private volatile boolean guestRunning;
    public boolean isGuestRunning() { return guestRunning; }
    private BooleanSupplier fossilizeCancellation;
    public void setFossilizeCancellation(BooleanSupplier cancellation) {
        fossilizeCancellation = cancellation;
    }
    @Override
    public void start() {
        synchronized (lock) {
            stop();
            sessionId = java.util.UUID.randomUUID().toString();
            if (wineInfo.isArm64EC())
                extractEmulatorsDlls();
            else
                extractBox64Files();
            if (preUnpack != null) preUnpack.run();
            pid = execGuestProgram();
            if (gamescopeMode && pid < 0) throw new IllegalStateException("Gamescope process could not start");
            Log.d("BionicProgramLauncherComponent", "Process " + pid + " started");
            SteamService.acquireKeepAlive(sessionId);
            guestRunning = pid > 0;
            if (guestRunning && onGuestStarted != null) onGuestStarted.run();
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            guestRunning = false;
            gamescopeGuestEnv = null;
            if (pid != -1) {
                // Kill only this session's process tree (recursive PPID walk);
                // other containers' sessions are left alone.
                java.util.Set<Integer> descendants = descendantPids(pid);
                descendants.remove(pid);
                Process.killProcess(pid);
                Log.d("BionicProgramLauncherComponent", "Stopped process " + pid);
                for (int childPid : descendants) {
                    Process.killProcess(childPid);
                }
                SteamService.releaseKeepAlive(sessionId);
            }
            // wineserver -k is scoped to this container's prefix via the env
            // built in execShellCommand (HOME/WINEPREFIX are per-container).
            try {
                execShellCommand("wineserver -k");
            } finally {
                if (container != null) FEXCoreManager.deleteContainerConfig(container.getRootDir());
            }
        }
    }

    /** All PIDs whose ancestor chain leads back to rootPid (inclusive of root). */
    private static java.util.Set<Integer> descendantPids(int rootPid) {
        java.util.Map<Integer, Integer> ppidByPid = new java.util.HashMap<>();
        for (ProcessHelper.ProcessInfo info : ProcessHelper.listSubProcesses()) {
            ppidByPid.put(info.pid, info.ppid);
        }
        java.util.Set<Integer> descendants = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        queue.add(rootPid);
        while (!queue.isEmpty()) {
            int parent = queue.poll();
            if (!descendants.add(parent)) continue;
            for (java.util.Map.Entry<Integer, Integer> entry : ppidByPid.entrySet()) {
                if (entry.getValue() == parent) {
                    queue.add(entry.getKey());
                }
            }
        }
        return descendants;
    }

    public Callback<Integer> getTerminationCallback() {
        return terminationCallback;
    }

    public void setTerminationCallback(Callback<Integer> terminationCallback) {
        this.terminationCallback = terminationCallback;
    }

    public String getGuestExecutable() {
        return guestExecutable;
    }

    public void setGuestExecutable(String guestExecutable) {
        this.guestExecutable = guestExecutable;
    }

    public boolean isWoW64Mode() {
        return wow64Mode;
    }

    public void setWoW64Mode(boolean wow64Mode) {
        this.wow64Mode = wow64Mode;
    }

    public String[] getBindingPaths() {
        return bindingPaths;
    }

    public void setBindingPaths(String[] bindingPaths) {
        this.bindingPaths = bindingPaths;
    }

    public EnvVars getEnvVars() {
        return envVars;
    }

    public void setEnvVars(EnvVars envVars) {
        this.envVars = envVars;
    }

    public String getBox64Preset() {
        return box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public void setFEXCorePreset (String fexcorePreset) { this.fexcorePreset = fexcorePreset; }

    public File getWorkingDir() {
        return workingDir;
    }

    public void setWorkingDir(File workingDir) {
        this.workingDir = workingDir;
    }

    private int execGuestProgram() {

        final int MAX_PLAYERS = 4;

        Context context = environment.getContext();

        // Get the number of enabled players directly from ControllerManager.
        File gamepadShmDir = new File(context.getFilesDir(), "gamepad_shm");
        for (int i = 0; i < MAX_PLAYERS; i++) {
            // Player 1 uses the original, non-numbered path that is known to work.
            String memName = (i == 0) ? "gamepad.mem" : "gamepad" + i + ".mem";
            File memFile = new File(gamepadShmDir, memName);
            memFile.getParentFile().mkdirs();
            try (RandomAccessFile raf = new RandomAccessFile(memFile, "rw")) {
                raf.setLength(64);
            } catch (IOException e) {
                Log.e("EVSHIM_HOST", "Failed to create mem file for player index "+i, e);
            }
        }
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();

        PrefManager.init(context);
        boolean enableBox86_64Logs = PrefManager.getBoolean("enable_box86_64_logs", true);
        boolean shareAndroidClipboard = PrefManager.getBoolean("share_android_clipboard", false);
        boolean enablePebLogs = PrefManager.getBoolean("enable_peb_logs", false);

        final boolean isArm64EC = wineInfo.isArm64EC();
        final boolean isGlibcVariant = container != null
                && Container.DEFAULT_VARIANT.equals(container.getContainerVariant());
        final CpuBackendType backend = CpuBackendType.resolve(
                isArm64EC, isGlibcVariant, container != null ? container.getEmulator() : "");

        // Single environment, built once, in strict layer order:
        //   1. essential system values
        //   2. selected backend (only the selected one)
        //   3. container env
        //   4. game-specific env (LSFG, real Steam)
        //   5. debug overrides
        EnvVars launchEnv = new EnvVars();

        // --- Layer 1: essential system values -------------------------------
        launchEnv.put("EVSHIM_BASE_PATH", context.getFilesDir().getAbsolutePath());
        launchEnv.put("EVSHIM_MAX_PLAYERS", String.valueOf(MAX_PLAYERS));
        launchEnv.put("EVSHIM_SHM_ID", 1);
        launchEnv.put("HOME", imageFs.home_path);
        launchEnv.put("USER", ImageFs.USER);
        launchEnv.put("TMPDIR", rootDir.getPath() + "/usr/tmp");
        new File(imageFs.home_path + "/.wine/drive_c" + rootDir.getPath() + "/usr/tmp").mkdirs();
        if (!gamescopeMode) launchEnv.put("DISPLAY", ":0");

        String winePath = resolveWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        launchEnv.put("PATH", winePath + ":" +
                rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) launchEnv.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + resolveWinePath() + "/lib";
        launchEnv.put("LD_LIBRARY_PATH", ldLibraryPath);
        launchEnv.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        launchEnv.put("FONTCONFIG_PATH", rootDir.getPath() + "/usr/etc/fonts");

        launchEnv.put("XDG_DATA_DIRS", rootDir.getPath() + "/usr/share");
        launchEnv.put("XDG_CONFIG_DIRS", rootDir.getPath() + "/usr/etc/xdg");
        launchEnv.put("GST_PLUGIN_PATH", rootDir.getPath() + "/usr/lib/gstreamer-1.0");
        launchEnv.put("VK_LAYER_PATH", rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d" + ":" + rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d");
        launchEnv.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        launchEnv.put("PREFIX", rootDir.getPath() + "/usr");
        launchEnv.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        launchEnv.put("ENABLE_UTIL_LAYER", "1");
        launchEnv.put("GST_PLUGIN_FEATURE_RANK", "ximagesink:3000");
        launchEnv.put("ALSA_CONFIG_PATH", rootDir.getPath() + "/usr/share/alsa/alsa.conf" + ":" + rootDir.getPath() + "/usr/etc/alsa/conf.d/android_aserver.conf");
        launchEnv.put("ALSA_PLUGIN_DIR", rootDir.getPath() + "/usr/lib/alsa-lib");
        launchEnv.put("OPENSSL_CONF", rootDir.getPath() + "/usr/etc/tls/openssl.cnf");
        launchEnv.put("SSL_CERT_FILE", rootDir.getPath() + "/usr/etc/tls/cert.pem");
        launchEnv.put("SSL_CERT_DIR", rootDir.getPath() + "/usr/etc/tls/certs");
        launchEnv.put("WINE_X11FORCEGLX", "1");
        launchEnv.put("WINE_GST_NO_GL", "1");
        launchEnv.put("SteamGameId", "0");
        launchEnv.put("WINE_OPEN_WITH_ANDROID_BROwSER", "1"); // Pipetto wine has a typo, so we need 2 envvar for it to work
        launchEnv.put("WINE_OPEN_WITH_ANDROID_BROWSER", "1");

        String primaryDNS = "8.8.4.4";
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);
        if (connectivityManager.getActiveNetwork() != null) {
            ArrayList<InetAddress> dnsServers = new ArrayList<>(connectivityManager.getLinkProperties(connectivityManager.getActiveNetwork()).getDnsServers());

            // Check if the dnsServers list is not empty before getting an item
            if (!dnsServers.isEmpty()) {
                primaryDNS = dnsServers.get(0).toString().substring(1);
            }
        }
        launchEnv.put("ANDROID_RESOLV_DNS", primaryDNS);
        launchEnv.put("WINE_NEW_NDIS", "1");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String evshimPath = context.getApplicationInfo().nativeLibraryDir + "/libevshim.so";
        String replacePath = imageFs.getLibDir() + "/" + BuildConfig.PRELOAD_BIONIC_SO;

        if (new File(sysvPath).exists()) ld_preload += sysvPath;


        ld_preload += ":" + evshimPath;
        ld_preload += ":" + replacePath;

        launchEnv.put("LD_PRELOAD", ld_preload);
        launchEnv.put("EVSHIM_WINE", 1);
        launchEnv.put("EVSHIM_SHM_NAME", "controller-shm0");

        // --- Layer 2: selected backend only ----------------------------------
        if (backend.appliesBox64Env()) {
            addBox64EnvVars(launchEnv, enableBox86_64Logs);

            String renderer = GPUInformation.getRenderer(context);
            if (renderer.contains("Mali"))
                launchEnv.put("BOX64_MMAP32", "0");

            if ("1".equals(launchEnv.get("BOX64_MMAP32")) && !isArm64EC)
                launchEnv.put("WRAPPER_DISABLE_PLACED", "1");
        }
        EnvVars fexConfig = new EnvVars();
        if (backend.appliesFexEnv()) {
            EnvVars fexPreset = FEXCorePresetManager.getEnvVars(context, fexcorePreset);
            if (container != null && container.getRootDir() != null) {
                fexConfig = FEXCoreManager.takeAppConfigOptions(fexPreset);
            }
            launchEnv.putAll(fexPreset);
        }

        // --- Layer 3: container env ------------------------------------------
        if (this.envVars != null) {
            launchEnv.putAll(this.envVars);
        }

        // --- Layer 4: game-specific env ---------------------------------------
        // Bionic-Steam mode: env vars required by Proton's lsteamclient.dll +
        // native libsteamclient.so bridge (loader path, IPC endpoint, VDF root).
        if (container != null && container.isLaunchBionicSteam()) {
            addRealSteamEnvVars(launchEnv, imageFs);
            // Boot the native libsteamclient.so inside *this* (Android) process
            // so Wine-side lsteamclient.dll has something to connect to.
            bootstrapNativeSteamClient(launchEnv, imageFs);
        }

        if (BuildConfig.XR_BUILD) {
            String shimPath = context.getApplicationInfo().nativeLibraryDir + "/libkgslshim.so";
            if (new File(shimPath).exists()) {
                String cur = launchEnv.get("LD_PRELOAD");
                launchEnv.put("LD_PRELOAD", cur.isEmpty() ? shimPath : shimPath + ":" + cur);
            }
        }

        if (LsfgVkManager.isSupported(container)) {
            if (gamescopeMode) {
                LsfgVkManager.prepareDrmRuntime(context, container);
                LsfgQuickMenuHelper.applyDrmSettings(container);
            } else {
                LsfgVkManager.ensureRuntimeInstalled(context, container);
                LsfgVkManager.writeConfig(container);
                LsfgVkManager.applyLaunchEnv(container, launchEnv);
            }
        }

        // --- Layer 5: debug overrides -----------------------------------------
        if (shareAndroidClipboard) {
            launchEnv.put("WINE_FROM_ANDROID_CLIPBOARD", "1");
            launchEnv.put("WINE_TO_ANDROID_CLIPBOARD", "1");
        }
        if (enablePebLogs) {
            launchEnv.put("WINE_LOG_PEB_DATA", "1");
        }
        if (gamescopeMode) {
            applyGamescopeEnv(context, launchEnv, rootDir, container, gamescopeDisplay, gamescopeRuntime(), gamescopeIcd);
        }

        // Experimental FEX translation cache: preparation/load is per game and only applied
        // when the installed FEX build ships the offline compiler.
        if (container != null) {
            java.util.Map<String, String> fexCacheEnv = FexCodeCache.launchEnv(context, container);
            if (fexCacheEnv != null) {
                for (java.util.Map.Entry<String, String> entry : fexCacheEnv.entrySet()) {
                    launchEnv.put(entry.getKey(), entry.getValue());
                }
            }
        }

        if (!fexConfig.isEmpty() && !FEXCoreManager.writeContainerConfig(container.getRootDir(), fexConfig)) {
            Log.w("BionicProgramLauncherComponent", "Could not write the FEX container config. Using environment variables.");
            for (String name : fexConfig) {
                if (!launchEnv.has(name)) launchEnv.put(name, fexConfig.get(name));
            }
        }

        ContainerOverlay.applyBionicLaunchEnv(context, container, launchEnv);
        if (toolMode) {
            String libDir = imageFs.getLibDir().getPath();
            launchEnv.put("LD_PRELOAD", libDir + "/libandroid-sysvshm.so:"
                    + libDir + "/libgnoverlay.so:"
                    + libDir + "/" + BuildConfig.PRELOAD_BIONIC_SO);
            java.util.ArrayList<String> box64Keys = new java.util.ArrayList<>();
            for (String name : launchEnv) if (name.startsWith("BOX64_")) box64Keys.add(name);
            for (String name : box64Keys) launchEnv.remove(name);
        }
        Log.d("BionicProgramLauncherComponent", "env vars are " + EnvRedactor.redact(launchEnv));

        // Construct the command without Box64 to the Wine executable
        String command = "";
        String overriddenCommand = launchEnv.get("GUEST_PROGRAM_LAUNCHER_COMMAND");
        if (!overriddenCommand.isEmpty()) {
            String[] parts = overriddenCommand.split(";");
            for (String part : parts)
                command += part + " ";
            command = command.trim();
        }
        else {
            command = getFinalCommand(winePath, backend, launchEnv, imageFs.getBinDir(), guestExecutable);
        }
        boolean guestWrapperIcd = gamescopeMode && container != null
                && launchEnv.get("GNDRM_DRIVER_TYPE").regionMatches(true, 0, "wrapper", 0, "wrapper".length())
                && !launchEnv.has(VKSHIM_SYSTEM_DRIVER)
                && new AdrenotoolsManager(context).isSd8Gen2TurboDriver(launchEnv);
        // This package needs the wrapper ICD in Wine and replay. The wrapper still opens
        // the selected Turnip driver using the Adrenotools environment.
        if (guestWrapperIcd) launchEnv.put(VKSHIM_SYSTEM_DRIVER, "1");
        if (container != null && fossilizeCancellation != null) {
            launchEnv.remove("FOSSILIZE");
            if (FossilizeManager.prepare(context, container, launchEnv, fossilizeCancellation)) {
                command = "/system/bin/env FOSSILIZE=1 " + command;
            }
            if (fossilizeCancellation.getAsBoolean()) {
                throw new IllegalStateException("Launch cancelled before the guest started");
            }
        }
        if (gamescopeMode) {
            EnvVars guestEnv = launchEnv;
            if (guestWrapperIcd) {
                guestEnv = new EnvVars();
                guestEnv.putAll(launchEnv);
                launchEnv.remove(VKSHIM_SYSTEM_DRIVER);
                command = "/system/bin/env " + VKSHIM_SYSTEM_DRIVER + "=1 " + command;
            }
            command = wrapWithGamescope(command);
            gamescopeGuestEnv = guestEnv;
        }

        // **Maybe remove this: Set execute permissions for box64 if necessary (Glibc/Proot artifact)
        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        return ProcessHelper.exec(command, launchEnv.toStringArray(), workingDir != null ? workingDir : rootDir, (status) -> {
            synchronized (lock) {
                guestRunning = false;
                pid = -1;
                gamescopeGuestEnv = null;
            }
            if (!environment.isWinetricksRunning()) {
                SteamService.releaseKeepAlive(sessionId);
                if (terminationCallback != null)
                    terminationCallback.call(status);
            }
        });
    }

    @NonNull
    private String getFinalCommand(String winePath, CpuBackendType backend, EnvVars envVars, File binDir, String guestExecutable) {
        // guestExecutable is already a complete command line assembled by the
        // launch pipeline (wine subcommand + executable + arguments). Quoting it
        // as one shell token makes the entire line look like an executable name.
        // A future argv migration must quote/encode individual tokens upstream.
        String command;
        if (wineInfo.isArm64EC()) {
            command = winePath + "/" + guestExecutable;
            String hodll = backend.hodll();
            if (hodll != null) envVars.put("HODLL", hodll);
        }
        else
            command = binDir + "/box64 " + guestExecutable;
        return command;
    }

    // Gamescope payload paths: everything is payload-relative (build-host prefixes don't exist on device).
    static void applyGamescopeEnv(Context context, EnvVars envVars, File rootDir, Container container) {
        applyGamescopeEnv(context, envVars, rootDir, container, null, GamescopePayload.INSTANCE.directory(context.getFilesDir()), null);
    }

    private static void applyGamescopeEnv(Context context, EnvVars envVars, File rootDir, Container container,
                                          Display display, File payload, File icdOverride) {
        String gsDir = payload.getAbsolutePath();
        boolean experimentalGl = GamescopePayload.isExperimentalDirectory(payload);
        envVars.remove("DISPLAY");
        envVars.remove("MESA_VK_WSI_PRESENT_MODE");
        envVars.remove("WRAPPER_MAX_IMAGE_COUNT");
        envVars.remove("VK_DRIVER_FILES");
        boolean hdr = container != null && container.isHdrEnabled() && GamescopeSettings.hdrCapabilities(display) != null;
        envVars.put("GNDRM_HDR", hdr ? "1" : "0");
        if (hdr) envVars.put("DXVK_HDR", "1");

        // Append the shim, never prepend: its opens pass straight through to syscalls, so going first would hide the base prefix's DLLs from libgnoverlay/wine.
        String preload = envVars.get("LD_PRELOAD");
        envVars.put("LD_PRELOAD", preload.isEmpty()
                ? gsDir + "/lib/libgndrm_shim.so"
                : preload + ":" + gsDir + "/lib/libgndrm_shim.so");
        String gamescopeLibPath = gsDir + "/lib";
        String libPath = envVars.get("LD_LIBRARY_PATH");
        envVars.put("LD_LIBRARY_PATH", libPath.isEmpty() ? gamescopeLibPath : gamescopeLibPath + ":" + libPath);
        if (experimentalGl) {
            envVars.put("LD_LIBRARY_PATH", gsDir + "/lib/gl:" + envVars.get("LD_LIBRARY_PATH"));
            envVars.remove("LIBGL_KOPPER_DISABLE");
            envVars.put("MESA_GLX_FORCE_DIRECT", "1");
        }
        // gamescope execvp's gamescopereaper and wlroots spawns xkbcomp.
        String path = envVars.get("PATH");
        envVars.put("PATH", path.isEmpty() ? gsDir + "/bin" : gsDir + "/bin:" + path);

        File xdgRunDir = new File(rootDir, "tmp/xdg-run");
        xdgRunDir.mkdirs();
        FileUtils.chmod(xdgRunDir, 0700);
        envVars.put("XDG_RUNTIME_DIR", xdgRunDir.getPath());
        envVars.put("GAMENATIVE_DRM_SOCKET", "gndrm");
        if (!envVars.has("GNDRM_SCANOUT_LAYOUT"))
            envVars.put("GNDRM_SCANOUT_LAYOUT", scanoutLayout());

        envVars.put("XKB_CONFIG_ROOT", gsDir + "/share/xkeyboard-config-2");
        envVars.put("GAMESCOPE_PNP_IDS_PATH", gsDir + "/share/hwdata/pnp.ids");
        envVars.put("__EGL_VENDOR_LIBRARY_DIRS", gsDir + "/share/glvnd/egl_vendor.d");
        envVars.put("LIBGL_DRIVERS_PATH", (experimentalGl ? gsDir + "/lib/gl/dri:" : "") + gsDir + "/lib/dri");
        envVars.put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
        envVars.put("WLR_XWAYLAND", gsDir + "/bin/Xwayland");

        // Non-Adreno (system Vulkan driver): glamor over zink stalls Xwayland, and Vulkan guests present through the shim.
        if (!new File("/dev/kgsl-3d0").exists())
            envVars.put("XWAYLAND_NO_GLAMOR", "1");

        // wlroots binds the X11 sockets here; gamescope's libX11 would resolve ":0" to a build-host dir, so it gets the socket path.
        String x11SocketDir = rootDir.getPath() + "/usr/tmp";
        new File(x11SocketDir).mkdirs();
        envVars.put("WLR_X11_SOCKET_DIR", x11SocketDir);
        envVars.put("GAMESCOPE_X11_DISPLAY", x11SocketDir + "/.X11-unix/X0");

        File sessionIcd = icdOverride != null ? icdOverride
                : new File(payload, "share/vulkan/icd.d/adrenotools_gamescope.aarch64.json");
        envVars.put("VK_ICD_FILENAMES", sessionIcd.getAbsolutePath());

        if (container != null && container.getGraphicsDriver() != null)
            envVars.put("GNDRM_DRIVER_TYPE", container.getGraphicsDriver());

        // Same source as gamescope's -W/-H and the daemon's mode; the panel is the fallback when the resolution is unreadable.
        int[] scanoutOutput = GamescopeSettings.scanoutSize(container, display);
        if (scanoutOutput == null)
            scanoutOutput = displaySize(display);
        if (scanoutOutput == null)
            scanoutOutput = gamescopeNestedSize(container);
        if (scanoutOutput != null)
            envVars.put("GNDRM_SCANOUT", scanoutOutput[0] + "x" + scanoutOutput[1]);

        // The nested (-w/-h) geometry, seeded the same way: Xwayland's first window-sized allocations
        // reach the daemon as a bare length, and without this it learns that size only from a later
        // commit, so those early buffers stay kernel-owned and get copied in.
        int[] nested = gamescopeNestedSize(container);
        if (nested != null)
            envVars.put("GNDRM_SCANOUT_NESTED", nested[0] + "x" + nested[1]);

        Log.i("BionicProgramLauncherComponent", "Gamescope display server active; bin="
                + new File(gsDir, "bin/gamescope").exists() + " shim="
                + new File(gsDir, "lib/libgndrm_shim.so").exists() + " xwayland="
                + new File(gsDir, "bin/Xwayland").exists() + " xdg=" + xdgRunDir.getPath());
    }

    private static String scanoutLayout() {
        try {
            return GameNativeDrm.nativeScanoutLayout();
        } catch (LinkageError e) {
            return "bgra";
        }
    }

    /** Prefix {@code command} with the gamescope wrapper used for guest launches. */
    private String wrapWithGamescope(String command) {
        String gamescope = new File(gamescopeRuntime(), "bin/gamescope").getAbsolutePath();
        linkReshadeShaders();
        // HDR output is asked for only when the container asks AND the panel reports HDR types.
        final boolean hdr = container != null && container.isHdrEnabled()
                && GamescopeSettings.hdrCapabilities(gamescopeDisplay) != null;
        // No --hdr-itm-enabled: inverse tone mapping is for SDR input and forces full composition on every
        // frame.
        // The effect the QuickMenu last chose is applied here too, so a relaunch keeps it. A path the
        // manifest no longer lists is dropped rather than handed to gamescope as a missing file.
        String reshade = container != null ? container.getExtra(GamescopeSettings.EXTRA_RESHADE, "") : "";
        if (!reshade.isEmpty() && !manifestShaders(environment.getContext()).contains(reshade)) reshade = "";
        return gamescope + " --backend drm --expose-wayland"
                + (hdr ? " --hdr-enabled" : "")
                + (reshade.isEmpty() ? "" : " --reshade-effect " + reshade)
                + GamescopeSettings.scalerArg(container)
                + gamescopeModeGeometry() + " -- " + command;
    }

    private File gamescopeRuntime() {
        return gamescopeRuntime != null ? gamescopeRuntime : GamescopePayload.INSTANCE.directory(environment.getContext().getFilesDir());
    }

    /** The effect paths `all_shaders.json` lists, as staged in `<files>/reshade`. */
    private static java.util.Set<String> manifestShaders(Context context) {
        java.util.Set<String> paths = new java.util.HashSet<>();
        try {
            java.io.File manifest = new java.io.File(context.getFilesDir(), "reshade/all_shaders.json");
            org.json.JSONArray entries = new org.json.JSONArray(FileUtils.readString(manifest));
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String path = entry.optString("path");
                if (!path.isEmpty()) paths.add(path);
            }
        }
        catch (Exception e) {
            Log.w("BionicProgramLauncher", "ReShade manifest unreadable: " + e.getMessage());
        }
        return paths;
    }

    /**
     * Copy each pack under `assets/reshade` to `<files>/reshade/Shaders/<pack>` and the manifest to
     * the root, the layout gamescope resolves an effect path against. The staged copy is rebuilt every
     * launch so it cannot drift from the APK.
     */
    private static void stageReshadeShaders(Context context, File root) {
        // The APK is the source of truth: a shader removed from it must disappear on device too.
        if (root.exists()) FileUtils.delete(root);
        root.mkdirs();
        try {
            String[] entries = context.getAssets().list("reshade");
            if (entries == null) return;
            for (String entry : entries) {
                // copy() appends the asset's own name only when the destination is a directory
                // already, so each destination is built rather than left to it.
                if (FileUtils.isDirectory(context, "reshade/" + entry))
                    FileUtils.copy(context, "reshade/" + entry, new File(new File(root, "Shaders"), entry));
                else FileUtils.copy(context, "reshade/" + entry, new File(root, entry));
            }
        }
        catch (java.io.IOException e) {
            Log.w("BionicProgramLauncher", "ReShade staging failed: " + e.getMessage());
        }
    }

    /**
     * Stage the bundled ReShade shaders and point the container home at them. gamescope resolves an
     * effect name against `$HOME/.local/share/gamescope/reshade/Shaders`, and `$HOME` is the shared
     * `home/xuser` link, so the shaders are copied out of the APK once per launch and only the link
     * varies by container.
     */
    private void linkReshadeShaders() {
        Context context = environment.getContext();
        File shaders = new File(context.getFilesDir(), "reshade");
        stageReshadeShaders(context, shaders);
        File target = new File(new File(context.getFilesDir(), "imagefs/home/xuser"), ".local/share/gamescope/reshade");
        try {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            if (java.nio.file.Files.isSymbolicLink(target.toPath()) || target.isFile())
                target.delete();
            else if (target.isDirectory())
                com.winlator.core.FileUtils.delete(target);
            com.winlator.core.FileUtils.symlink(shaders.getAbsolutePath(), target.getPath());
            Log.i("BionicProgramLauncher", "ReShade shaders linked from " + shaders);
        }
        catch (Exception e) {
            Log.w("BionicProgramLauncher", "ReShade shader link failed: " + e.getMessage());
        }
    }

    /** " -W <w> -H <h> -w <w> -h <h>": -W/-H from the container's output resolution (the container screen size when Off, the panel when Device's), -w/-h from the container screen size; either pair omitted if unknown. */
    private String gamescopeModeGeometry() {
        int[] containerSize = gamescopeNestedSize(container);
        StringBuilder args = new StringBuilder(GamescopeSettings.outputGeometryArg(container, gamescopeDisplay));
        if (containerSize != null) args.append(" -w ").append(containerSize[0]).append(" -h ").append(containerSize[1]);
        return args.toString();
    }

    /** The container's screen size — the nested (-w/-h) geometry of a gamescope session — or null if unreadable. */
    private static int[] gamescopeNestedSize(Container container) {
        return AppUtils.parseScreenSize(container != null ? container.getScreenSize() : null);
    }

    /** The panel's real pixel size, or null if unreadable; only the shim seed's last-resort fallback. */
    private static int[] displaySize(Display display) {
        int[] nativeSize = AppUtils.parseScreenSize(GamescopeSettings.displayResolution(display));
        if (nativeSize != null) return nativeSize;
        return AppUtils.parseScreenSize(PluviaApp.getDefaultScreenSize());
    }

    private void extractBox64Files() {
        ImageFs imageFs = environment.getImageFs();
        Context context = environment.getContext();
        String box64Version = container.getBox64Version();

        Log.i("Extraction", "Extracting required box64 version: " + box64Version);
        File rootDir = imageFs.getRootDir();

        // No more version check, just extract directly.
        ContentProfile profile = contentsManager.getProfileByEntryName("box64-" + box64Version);
        if (profile != null) {
            contentsManager.applyContent(profile);
        } else {
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context.getAssets(), "box86_64/box64-" + box64Version + "-bionic.tzst", rootDir);
        }

        // Update the metadata so the container knows which version is installed.
        container.putExtra("box64Version", box64Version);
        container.saveData();

        // Set execute permissions.
        File box64File = new File(rootDir, "usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }
    }

    private void extractEmulatorsDlls() {
        Context context = environment.getContext();
        File system32dir = new File(environment.getImageFs().getRootDir(), "home/xuser/.wine/drive_c/windows/system32");
        CpuBackendType backend = CpuBackendType.resolve(true, false, container.getEmulator());

        // ARM64EC Proton needs FEX for x86-64 code even when HODLL selects WowBox64 for x86.
        String fexcoreVersion = container.getFEXCoreVersion();
        ContentProfile fexprofile = contentsManager.getProfileByEntryName("fexcore-" + fexcoreVersion);
        boolean installed = fexprofile != null
                ? contentsManager.applyContent(fexprofile)
                : TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context,
                        "fexcore/fexcore-" + fexcoreVersion + ".tzst", system32dir);
        if (!installed || new File(system32dir, "libarm64ecfex.dll").length() == 0) {
            throw new IllegalStateException("Failed to install libarm64ecfex.dll from FEXCore " + fexcoreVersion);
        }
        container.putExtra("fexcoreVersion", fexcoreVersion);

        if (backend == CpuBackendType.WOWBOX64_ARM64EC) {
            String wowbox64Version = container.getBox64Version();
            Log.d("Extraction", "Extracting selected backend wowbox64: " + wowbox64Version);

            ContentProfile wowboxprofile = contentsManager.getProfileByEntryName("wowbox64-" + wowbox64Version);
            if (wowboxprofile != null) {
                contentsManager.applyContent(wowboxprofile);
            } else {
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, environment.getContext(), "wowbox64/wowbox64-" + wowbox64Version + ".tzst", system32dir);
            }
            container.putExtra("box64Version", wowbox64Version);
        }

        container.saveData();
    }

    private void addBox64EnvVars(EnvVars envVars, boolean enableLogs) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("BOX64_NOBANNER", ProcessHelper.PRINT_DEBUG && enableLogs ? "0" : "1");
        envVars.put("BOX64_DYNAREC", "1");

        if (enableLogs) {
            envVars.put("BOX64_LOG", "1");
            envVars.put("BOX64_DYNAREC_MISSING", "1");
        }

        envVars.putAll(Box86_64PresetManager.getEnvVars("box64", environment.getContext(), box64Preset));
        envVars.put("BOX64_X11GLX", "1");
        File box64RCFile = new File(imageFs.getRootDir(), "/etc/config.box64rc");
        envVars.put("BOX64_RCFILE", box64RCFile.getPath());
    }

    /**
     * Sets env vars required when launching with the real Steam client
     * (Proton's steam.exe + lsteamclient.dll talking to the native libsteamclient.so).
     *
     * Three groups:
     *   A. WINESTEAMCLIENTPATH{,64} — where lsteamclient.dll dlopens the native bridge.
     *   B. _STEAM_SETENV_MANAGER + friends — bootstrap-gate handshake checked by
     *      libsteamclient.so's init_process_env_manager_from_dll, plus the IPC endpoint
     *      overrides resolved in its endpoint string parser.
     *   C. SteamPath / SteamUser / SteamClientLaunch — Wine-side identity expected by
     *      steam_helper (steam.exe) and Steamworks games inside the prefix.
     *
     * STEAM_BASE_FOLDER points the native .so at the Linux-side directory that mirrors
     * the Windows `C:\Program Files (x86)\Steam` install, so its `<base>/config/config.vdf`
     * read lands on the file SteamTokenLogin.phase1SteamConfig() writes.
     */
    private void addRealSteamEnvVars(EnvVars envVars, ImageFs imageFs) {
        String steamRootLinux = imageFs.wineprefix + "/drive_c/Program Files (x86)/Steam";
        String breakpadDir = imageFs.getRootDir().getPath() + "/usr/tmp/breakpad";
        new File(breakpadDir).mkdirs();

        // A. lsteamclient.dll loader paths -> native Linux client/bridge .so
        envVars.put("WINESTEAMCLIENTPATH64", steamRootLinux + "/linux64/steamclient.so");
        envVars.put("WINESTEAMCLIENTPATH",   steamRootLinux + "/linux32/steamclient.so");

        // B. libsteamclient.so bootstrap-gate handshake (all required together)
        envVars.put("_STEAM_SETENV_MANAGER", "1");
        envVars.put("BREAKPAD_DUMP_LOCATION", breakpadDir);
        envVars.put("STEAM_BASE_FOLDER", steamRootLinux);
        envVars.put("ENABLE_VK_LAYER_VALVE_steam_overlay_1", "0");
        // ISteamUtils::IsOverlayEnabled() is not engine state -- it resolves in-process to
        // `getenv("SteamOS") ? true : dlsym(RTLD_DEFAULT, "IsOverlayEnabled")`. No overlay
        // module is loaded here (the bionic asset set ships none), so without this it returns
        // false and games that gate their invite/host UI on it refuse to open it.
        envVars.put("SteamOS", "1");
        envVars.put("STEAMVIDEOTOKEN", "1");

        // IPC endpoints; override defaults if the MCP-hosted .so listens elsewhere
        envVars.put("Steam3Master",       "127.0.0.1:57343");
        envVars.put("SteamClientService", "127.0.0.1:57344");

        // C. Wine-side Steam identity for steam_helper / games
        String username = app.gamenative.PrefManager.INSTANCE.getUsername();
        if (username != null && !username.isEmpty()) {
            envVars.put("SteamUser", username);
            // Mirrors what the real Steam client publishes; some Steamworks
            // games (and steam_helper itself) read SteamAppUser as a fallback.
            envVars.put("SteamAppUser", username);
        }
        envVars.put("SteamClientLaunch", "1");
        envVars.put("SteamEnv", "1");
        envVars.put("SteamPath", "C:\\Program Files (x86)\\Steam");
        envVars.put("ValvePlatformMutex", "c:\\Program Files (x86)\\Steam/");

        long steamId64 = app.gamenative.PrefManager.INSTANCE.getSteamUserSteamId64();
        if (steamId64 != 0L) {
            envVars.put("STEAMID", Long.toString(steamId64));
        }

        // Override the SteamGameId=0 set above with the actual Steam appid for
        // this container, and publish the matching SteamAppId. Steamworks games
        // (and the steam.exe / steam_helper handshake) require both to be set
        // to the running game's appid for the IPC bridge to attach correctly.
        if (steamAppId != null && !steamAppId.isEmpty()) {
            envVars.put("SteamGameId", steamAppId);
            envVars.put("SteamAppId", steamAppId);
        }
    }

    /**
     * Loads libsteamclient.so into the Android process (via JNI -> dlopen) and
     * brings it up to a connected SteamClient + pipe + global-user state, so
     * Proton's lsteamclient.dll inside the Wine subprocess we launch next has
     * a live IPC peer.
     *
     * HOME handling: the native init calls setenv("HOME", ...) on the Android
     * process. The Wine subprocess we spawn afterwards is invoked with an
     * explicit envp via ProcessHelper.exec(...), which already contains the
     * project's own HOME (imageFs.home_path) — so the value we set here does
     * NOT leak into Wine.
     */
    private void bootstrapNativeSteamClient(EnvVars envVars, ImageFs imageFs) {
        // HOME for libsteamclient.so points at the parent of `Steam/`; the .so
        // resolves <HOME>/Steam/config/config.vdf etc. relative to it.
        String nativeHome = imageFs.wineprefix + "/drive_c/Program Files (x86)";
        // The Android-Steam build of libsteamclient.so ships inside our imagefs
        // (e.g. /data/data/app.gamenative/files/imagefs/usr/lib/libsteamclient.so).
        String libPath = new File(imageFs.getLibDir(), "libsteamclient.so").getAbsolutePath();

        File libFile = new File(libPath);
        if (!libFile.exists()) {
            Log.w("BionicProgramLauncherComponent",
                  "libsteamclient.so not found at " + libPath + "; skipping native bootstrap");
            return;
        }

        java.util.HashMap<String, String> extra = new java.util.HashMap<>();
        // bootstrap-gate handshake vars libsteamclient.so checks during init
        String[] passthrough = new String[] {
                "_STEAM_SETENV_MANAGER",
                "BREAKPAD_DUMP_LOCATION",
                "STEAM_BASE_FOLDER",
                "ENABLE_VK_LAYER_VALVE_steam_overlay_1",
                "STEAMVIDEOTOKEN",
                "SteamUser",
                "SteamOS",
        };
        for (String key : passthrough) {
            String val = envVars.get(key);
            if (val != null && !val.isEmpty()) {
                extra.put(key, val);
            }
        }

        // Credentials for the explicit refresh-token logon path inside the .so.
        // PrefManager.username is the Steam account login name; refreshToken is
        // the JWT-style token Steam issued during our app login; steamUserSteamId64
        // is the 64-bit SteamID. If any are missing we fall back to whatever
        // cached auto-logon libsteamclient.so can do on its own.
        String accountName  = app.gamenative.PrefManager.INSTANCE.getUsername();
        String refreshToken = app.gamenative.PrefManager.INSTANCE.getRefreshToken();
        long   steamId64    = app.gamenative.PrefManager.INSTANCE.getSteamUserSteamId64();

        try {
            int rc = app.gamenative.SteamBootstrap.INSTANCE.start(
                    environment.getContext(),
                    libPath,
                    nativeHome,
                    envVars.get("Steam3Master"),
                    envVars.get("SteamClientService"),
                    extra,
                    accountName,
                    refreshToken,
                    steamId64);
            Log.i("BionicProgramLauncherComponent", "SteamBootstrap.start rc=" + rc);

            // Once the engine is logged on, kick PICS + encrypted-ticket
            // pre-warm for the app we're about to launch. The Wine-side
            // lsteamclient.dll has no path to drive these itself before the
            // game asks for an ownership ticket, so without this the launch
            // typically stalls at "Validating Subscriptions". Best-effort:
            // skipped silently if rc != 0 or steamAppId isn't a valid AppID.
            if (rc == 0 && steamAppId != null && !steamAppId.isEmpty()) {
                try {
                    int appIdInt = Integer.parseInt(steamAppId);
                    Log.i("BionicProgramLauncherComponent",
                          "SteamBootstrap.prepareApp(" + appIdInt + ")");
                    app.gamenative.SteamBootstrap.INSTANCE.prepareApp(appIdInt);
                } catch (NumberFormatException nfe) {
                    Log.w("BionicProgramLauncherComponent",
                          "steamAppId=" + steamAppId + " is not numeric; "
                          + "skipping SteamBootstrap.prepareApp");
                }
            }
        } catch (Throwable t) {
            Log.e("BionicProgramLauncherComponent", "SteamBootstrap.start threw", t);
        }
    }

    public String execShellCommand(String command) {
        return execShellCommand(command, true);
    }

    private String resolveWinePath() {
        if (wineInfo != null && wineInfo.path != null && !wineInfo.path.isEmpty()) {
            return wineInfo.path;
        }
        return ImageFs.find(environment.getContext()).getWinePath();
    }

    public String execShellCommand(String command, boolean includeStderr) {
        return execShellCommandInternal(command, includeStderr, false);
    }

    /** Runs through /system/bin/sh so the child program keeps its own argv[0]. */
    public String execShellCommandViaShell(String command) {
        return execShellCommandInternal(command, true, true);
    }

    private String execShellCommandInternal(String command, boolean includeStderr, boolean viaShell) {
        Context context = environment.getContext();
        ImageFs imageFs = ImageFs.find(context);
        File rootDir = imageFs.getRootDir();
        EnvVars envVars = new EnvVars();
        addBox64EnvVars(envVars, false);

        envVars.put("HOME", imageFs.home_path);
        envVars.put("USER", ImageFs.USER);
        envVars.put("TMPDIR", imageFs.getRootDir().getPath() + "/tmp");
        envVars.put("DISPLAY", ":0");

        String winePath = resolveWinePath() + "/bin";

        Log.d("BionicProgramLauncherComponent", "WinePath is " + winePath);

        envVars.put("PATH", winePath + ":" + rootDir.getPath() + "/usr/bin");
        if (BuildConfig.MODERN_ANDROID) envVars.put("REDIRECT_EXEC__PROC_SELF_EXE", winePath + "/wine");

        String ldLibraryPath = rootDir.getPath() + "/usr/lib" + ":" + "/system/lib64";
        if (BuildConfig.MODERN_ANDROID) ldLibraryPath += ":" + resolveWinePath() + "/lib";
        envVars.put("LD_LIBRARY_PATH", ldLibraryPath);
        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir.getPath() + UnixSocketConfig.SYSVSHM_SERVER_PATH);
        envVars.put("WINE_NO_DUPLICATE_EXPLORER", "1");
        envVars.put("PREFIX", rootDir.getPath() + "/usr");
        envVars.put("WINE_DISABLE_FULLSCREEN_HACK", "1");
        envVars.put("SteamGameId", "0");

        String ld_preload = "";
        String sysvPath = imageFs.getLibDir() + "/libandroid-sysvshm.so";
        String replacePath = imageFs.getLibDir() + "/" + BuildConfig.PRELOAD_BIONIC_SO;

        if (new File(sysvPath).exists()) ld_preload += sysvPath;

        ld_preload += ":" + replacePath;

        envVars.put("LD_PRELOAD", ld_preload);

        final CpuBackendType shellBackend = CpuBackendType.resolve(
                wineInfo.isArm64EC(),
                container != null && Container.DEFAULT_VARIANT.equals(container.getContainerVariant()),
                container != null ? container.getEmulator() : "");
        if (this.envVars != null) envVars.putAll(this.envVars);

        ContainerOverlay.applyBionicLaunchEnv(context, container, envVars);
        if (viaShell) {
            // The app's native lib dir may be hidden from a /system/bin/sh child. Use the
            // imagefs copies so the ARM64EC compiler gets the overlay and bionic shims.
            String libDir = imageFs.getLibDir().getPath();
            envVars.put("LD_PRELOAD", libDir + "/libandroid-sysvshm.so:"
                    + libDir + "/libgnoverlay.so:"
                    + libDir + "/" + BuildConfig.PRELOAD_BIONIC_SO);
            // The compiler is not a Box64 guest; BOX64_* makes the overlay pick the wrong
            // redirection table and breaks Wine's builtin lookup.
            java.util.ArrayList<String> box64Keys = new java.util.ArrayList<>();
            for (String name : envVars) if (name.startsWith("BOX64_")) box64Keys.add(name);
            for (String name : box64Keys) envVars.remove(name);
        }
        String finalCommand = getFinalCommand(winePath, shellBackend, envVars, imageFs.getBinDir(), command);

        File box64File = new File(rootDir, "/usr/bin/box64");
        if (box64File.exists()) {
            FileUtils.chmod(box64File, 0755);
        }

        Log.d("BionicProgramLauncherComponent", "Shell command is " + EnvRedactor.redactText(finalCommand));
        if (viaShell) {
            return ProcessHelper.execWithOutputArgv(
                    new String[] {"/system/bin/sh", "-c", finalCommand},
                    envVars.toStringArray(),
                    workingDir != null ? workingDir : imageFs.getRootDir());
        }
        return ProcessHelper.execWithOutput(finalCommand, envVars.toStringArray(),
                workingDir != null ? workingDir : imageFs.getRootDir(), includeStderr);
    }

    /** The activation helper joins the current Wineserver and XWayland session. */
    public String execGamescopeShellCommand(String command) {
        EnvVars guestEnv = gamescopeGuestEnv;
        if (guestEnv == null) return "";
        EnvVars helperEnv = new EnvVars();
        helperEnv.putAll(guestEnv);
        helperEnv.put("DISPLAY", ":0");
        ImageFs imageFs = environment.getImageFs();
        CpuBackendType backend = CpuBackendType.resolve(
                wineInfo.isArm64EC(), Container.DEFAULT_VARIANT.equals(container.getContainerVariant()),
                container.getEmulator());
        String finalCommand = getFinalCommand(imageFs.getWinePath() + "/bin", backend,
                helperEnv, imageFs.getBinDir(), command);
        return ProcessHelper.execWithOutput(finalCommand, helperEnv.toStringArray(),
                workingDir != null ? workingDir : imageFs.getRootDir(), true);
    }

    public void restartWineServer() {
        guestRunning = false;
        ProcessHelper.terminateAllWineProcesses();
        pid = execGuestProgram();
        guestRunning = pid > 0;
        if (guestRunning && onGuestStarted != null) onGuestStarted.run();
        Log.d("BionicProgramLauncherComponent", "Wine restarted successfully");

    }
}
