package com.winlator.fexcore;

import android.content.Context;

import com.winlator.container.Container;
import com.winlator.contents.ContentsManager;
import com.winlator.core.FileUtils;
import com.winlator.core.WineInfo;
import com.winlator.xenvironment.ImageFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the Windows FEXOfflineCompiler64.exe inside the same Wine prefix as the game.
 *
 * FEX 2611-gn ships the compiler next to libwow64fex.dll. The regular Android launcher prefixes
 * /system/bin/linker64, which makes the ARM64EC compiler resolve Wine's builtins from the linker
 * and fail with "could not load kernel32.dll"; the small native bridge keeps wine as argv[0].
 */
public final class FexCacheToolRunner {
    private FexCacheToolRunner() {
    }

    public static String runCompiler(Context context, Container container, String windowsCacheDir) {
        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, container.getWineVersion());

        ImageFs imageFs = ImageFs.find(context);
        File wineBin = new File(wineInfo.path, "bin/wine");
        if (!wineBin.isFile()) return "compiler wine binary missing: " + wineBin;

        // The app's native lib dir may be hidden from the compiler child; use an imagefs copy.
        File overlaySource = new File(context.getApplicationInfo().nativeLibraryDir, "libgnoverlay.so");
        File overlayTarget = new File(imageFs.getLibDir(), "libgnoverlay.so");
        if (overlaySource.isFile() && (!overlayTarget.isFile() || overlayTarget.length() != overlaySource.length())) {
            FileUtils.copy(overlaySource, overlayTarget);
        }

        String home = container.getRootDir().getAbsolutePath();
        String prefix = new File(container.getRootDir(), ".wine").getAbsolutePath();
        String libDir = imageFs.getLibDir().getPath();
        String sharedRoot = new File(context.getFilesDir(), "imagefs_shared").getAbsolutePath();
        String wineRoot = wineInfo.path;
        String tmp = new File(imageFs.getRootDir(), "usr/tmp").getAbsolutePath();
        new File(tmp).mkdirs();

        List<String> env = new ArrayList<>();
        env.add("HOME=" + home);
        env.add("FEX_LAUNCHER_CWD=" + imageFs.getRootDir().getAbsolutePath());
        env.add("WINEPREFIX=" + prefix);
        env.add("USER=xuser");
        env.add("TMPDIR=" + tmp);
        env.add("DISPLAY=:0");
        env.add("SteamGameId=0");
        env.add("PATH=" + new File(wineRoot, "bin").getAbsolutePath() + ":" + imageFs.getRootDir().getPath() + "/usr/bin");
        env.add("LD_LIBRARY_PATH=" + libDir + ":/system/lib64:" + new File(wineRoot, "lib").getAbsolutePath());
        env.add("LD_PRELOAD=" + libDir + "/libandroid-sysvshm.so:" + libDir + "/libgnoverlay.so:"
                + libDir + "/" + app.gamenative.BuildConfig.PRELOAD_BIONIC_SO);
        env.add("HODLL=libwow64fex.dll");
        env.add("FEX_APP_CACHE_LOCATION=" + windowsCacheDir);
        env.add("FEX_SILENTLOG=0");
        env.add("FEX_OUTPUTLOG=" + windowsCacheDir + "compiler.log");
        env.add("WINEDEBUG=-all");
        String basePrefix = container.getExtra("basePrefix",
                new File(sharedRoot, "proton/proton-10.0-arm64ec/base_prefix/.wine").getAbsolutePath());
        env.add("GN_OVERLAY_UPPER=" + prefix);
        env.add("GN_OVERLAY_LOWER=" + basePrefix);
        env.add("GN_OVERLAY_ALIASES=" + imageFs.home_path + "/.wine:"
                + sharedRoot + "/home/xuser/.wine:"
                + home + "/.wine");
        env.add("GN_OVERLAY_HIDE=.DepotDownloader:.DownloadInfo");

        File log = new File(context.getFilesDir(), "fex-compiler.log");
        // The game may leave a wineserver with a different esync/fsync state; start a clean one
        // so the 32-bit compiler child gets the same environment as the validated reproduction.
        File wineserver = new File(wineRoot, "bin/wineserver");
        if (wineserver.isFile()) {
            FexNativeLauncher.runNative(wineserver.getAbsolutePath(), new String[] {"-k"},
                    env.toArray(new String[0]), log.getAbsolutePath());
        }
        int exit = 0;
        boolean is32Bit = is32BitExecutable(container);
        List<String> env32 = new ArrayList<>();
        for (String value : env) {
            if (value.startsWith("HODLL=") || value.startsWith("WINEDEBUG=")) continue;
            env32.add(value);
        }
        env32.add("WINEDEBUG=");
        File mainMap = findMainMap(container, windowsCacheDir);
        if (is32Bit && mainMap != null) {
            exit = runCompiler32Shell(context, container, imageFs, wineBin, windowsCacheDir,
                    windowsMapPath(windowsCacheDir, mainMap), log);
        } else if (is32Bit) {
            exit = FexNativeLauncher.runCompiler(
                    wineBin.getAbsolutePath(),
                    "C:\\windows\\system32\\FEXOfflineCompiler32.exe",
                    log.getAbsolutePath(),
                    env32.toArray(new String[0]));
        } else {
            exit = FexNativeLauncher.runCompiler(
                    wineBin.getAbsolutePath(),
                    "C:\\windows\\system32\\FEXOfflineCompiler64.exe",
                    log.getAbsolutePath(),
                    env.toArray(new String[0]));
        }

        // 32-bit games: process-all can consume the main code map before it writes a cache.
        // Retry with the main map directly, whether it is still in codemap/new or already moved.
        if (exit != 0) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            mainMap = findMainMap(container, windowsCacheDir);
            if (mainMap != null) {
                exit = runCompiler32Shell(context, container, imageFs, wineBin, windowsCacheDir,
                        windowsMapPath(windowsCacheDir, mainMap), log);
            }
        }

        String tail = log.isFile() ? FileUtils.readString(log) : "";
        if (tail.length() > 4000) tail = tail.substring(tail.length() - 4000);
        return "compiler exit " + exit + "\n" + tail;
    }

    private static int runCompiler32Shell(Context context, Container container, ImageFs imageFs,
                                          File wineBin, String windowsCacheDir, String windowsMap,
                                          File log) {
        String imageFsRoot = imageFs.getRootDir().getAbsolutePath();
        String libDir = imageFs.getLibDir().getPath();
        String home = container.getRootDir().getAbsolutePath();
        String prefix = new File(container.getRootDir(), ".wine").getAbsolutePath();
        String wineRoot = wineBin.getParentFile().getParentFile().getAbsolutePath();
        String basePrefix = container.getExtra("basePrefix", "");
        if (basePrefix.isEmpty()) basePrefix = new File(context.getFilesDir(),
                "imagefs_shared/proton/proton-10.0-arm64ec/base_prefix/.wine").getAbsolutePath();
        String script = "#!/system/bin/sh\n"
                + "export HOME='" + home + "'\n"
                + "export WINEPREFIX='" + prefix + "'\n"
                + "export USER=xuser\n"
                + "export TMPDIR='" + new File(imageFsRoot, "usr/tmp") + "'\n"
                + "export DISPLAY=:0\n"
                + "export WINEESYNC=1\n"
                + "export PATH='" + new File(wineRoot, "bin") + ":" + new File(imageFsRoot, "usr/bin") + "'\n"
                + "export LD_LIBRARY_PATH='" + libDir + ":/system/lib64:" + new File(wineRoot, "lib") + "'\n"
                + "export LD_PRELOAD='" + libDir + "/libandroid-sysvshm.so:"
                + libDir + "/libgnoverlay.so:" + libDir + "/" + app.gamenative.BuildConfig.PRELOAD_BIONIC_SO + "'\n"
                + "export FEX_APP_CACHE_LOCATION='" + windowsCacheDir + "'\n"
                + "export FEX_SILENTLOG=0\n"
                + "export GN_OVERLAY_UPPER='" + prefix + "'\n"
                + "export GN_OVERLAY_LOWER='" + basePrefix + "'\n"
                + "export GN_OVERLAY_ALIASES='" + imageFs.home_path + "/.wine:"
                + new File(context.getFilesDir(), "imagefs_shared/home/xuser/.wine") + ":"
                + home + "/.wine'\n"
                + "export GN_OVERLAY_HIDE=.DepotDownloader:.DownloadInfo\n"
                + "cd '" + imageFsRoot + "'\n"
                + "'" + wineBin.getAbsolutePath() + "' 'C:\\windows\\system32\\FEXOfflineCompiler32.exe' "
                + "generate --outdir '" + windowsCacheDir + "cache' '" + windowsMap + "'\n"
                + "echo EXIT:$?\n";
        File scriptFile = new File(context.getFilesDir(), "fex-compiler32.sh");
        FileUtils.writeString(scriptFile, script);
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", scriptFile.getAbsolutePath());
            pb.directory(imageFs.getRootDir());
            pb.environment().clear();
            pb.redirectErrorStream(true);
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            int exit = process.waitFor();
            FileUtils.writeString(log, output.toString());
            return exit;
        } catch (Exception e) {
            return 3;
        }
    }

    private static File findMainMap(Container container, String windowsCacheDir) {
        String mainName = new File(container.getExecutablePath().replace('\\', '/')).getName()
                .toLowerCase(java.util.Locale.ROOT);
        if (mainName.isEmpty()) return null;
        File hostCache = new File(new File(container.getRootDir(), ".wine/drive_c"),
                windowsCacheDir.substring(3).replace('\\', '/'));
        for (String sub : new String[] {"ready", "new"}) {
            File[] maps = new File(hostCache, "codemap/" + sub)
                    .listFiles((dir, name) -> name.startsWith(mainName + "-"));
            if (maps != null && maps.length > 0) {
                android.util.Log.d("FexCacheToolRunner", "main map " + sub + ": " + maps[0]);
                return maps[0];
            }
        }
        return null;
    }

    private static String windowsMapPath(String windowsCacheDir, File map) {
        return windowsCacheDir + "codemap\\" + map.getParentFile().getName() + "\\" + map.getName();
    }

    private static boolean is32BitExecutable(Container container) {
        try {
            String root = null;
            for (String[] drive : Container.drivesIterator(container.getDrives())) {
                if (drive.length >= 2 && "A".equalsIgnoreCase(drive[0])) {
                    root = drive[1];
                    break;
                }
            }
            if (root == null) return false;
            File exe = new File(root, container.getExecutablePath().replace('\\', '/'));
            android.util.Log.d("FexCacheToolRunner", "is32 root=" + root + " exe=" + exe + " exists=" + exe.isFile());
            if (!exe.isFile()) return false;
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(exe, "r")) {
                if (file.readUnsignedShort() != 0x4D5A) return false; // "MZ" big-endian read
                file.seek(0x3C);
                int pe = Integer.reverseBytes(file.readInt());
                file.seek(pe + 4);
                int machine = Short.reverseBytes(file.readShort()) & 0xFFFF;
                android.util.Log.d("FexCacheToolRunner", "is32 machine=" + Integer.toHexString(machine));
                return machine == 0x014C; // IMAGE_FILE_MACHINE_I386
            }
        } catch (Exception ignored) {
            return false;
        }
    }
}
