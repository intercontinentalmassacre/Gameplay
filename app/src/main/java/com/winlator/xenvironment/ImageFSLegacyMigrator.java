package com.winlator.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.contents.ContentProfile;
import com.winlator.contents.ContentsManager;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;

public final class ImageFSLegacyMigrator {
    private ImageFSLegacyMigrator() {}

    /**
     * Migrate legacy directories if needed. After that, ensure the shared home and proton are symlinked.
     */
    public static boolean migrateLegacyDirsIfNeeded(Context context, File legacyImageFsRoot, String wineVersion) {
        if (!migrateLegacyHomeToShared(context, legacyImageFsRoot)) {
            return false;
        }
        if (!migrateLegacyProtonToShared(context, legacyImageFsRoot)) {
            return false;
        }
        ImageFsInstaller.ensureSharedHomeRoot(context, legacyImageFsRoot);
        ImageFsInstaller.ensureProtonVersionSymlink(context, legacyImageFsRoot, wineVersion);
        repairLegacyCommonDllLinks(context);
        return true;
    }

    /**
     * Old prefixes can retain common Wine DLL symlinks into /opt/wine after the
     * Wine runtime was moved to imagefs_shared/proton. Those links become
     * dangling once the legacy directory disappears and Wine then fails before
     * it can load kernel32.dll. Repair only links with that exact old target;
     * regular files and user overrides remain untouched.
     */
    private static void repairLegacyCommonDllLinks(Context context) {
        File sharedHome = new File(ImageFs.getImageFsSharedDir(context), "home");
        File[] containerHomes = sharedHome.listFiles(file ->
                file.isDirectory() && file.getName().startsWith("xuser-"));
        if (containerHomes == null) return;

        ContentsManager contentsManager = new ContentsManager(context);
        contentsManager.syncContents();
        for (File containerHome : containerHomes) {
            String wineVersion = readContainerWineVersion(containerHome);
            if (!wineVersion.startsWith("proton-")) continue;

            File protonDir = resolveInstalledProtonDir(context, contentsManager, wineVersion);
            if (!protonDir.isDirectory()) continue;

            boolean arm64ec = wineVersion.contains("arm64ec");
            repairLegacyDllDirectory(
                    new File(containerHome, ".wine/drive_c/windows/system32"),
                    new File(protonDir, "lib/wine/" + (arm64ec ? "aarch64-windows" : "x86_64-windows"))
            );
            repairLegacyDllDirectory(
                    new File(containerHome, ".wine/drive_c/windows/syswow64"),
                    new File(protonDir, "lib/wine/i386-windows")
            );
        }
    }

    private static String readContainerWineVersion(File containerHome) {
        try {
            String json = FileUtils.readString(new File(containerHome, ".container"));
            return new JSONObject(json).optString("wineVersion", "");
        } catch (Exception exception) {
            Log.w("ImageFSLegacyMigrator", "Unable to read container config: " + containerHome.getName(), exception);
            return "";
        }
    }

    private static File resolveInstalledProtonDir(Context context, ContentsManager contentsManager, String wineVersion) {
        ContentProfile profile = contentsManager.getProfileByEntryName(wineVersion);
        if (profile != null && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            return ContentsManager.getInstallDir(context, profile);
        }
        return new File(ImageFs.getSharedProtonDir(context), wineVersion);
    }

    private static void repairLegacyDllDirectory(File destinationDir, File sourceDir) {
        File[] files = destinationDir.listFiles();
        if (files == null || !sourceDir.isDirectory()) return;

        for (File destination : files) {
            if (!Files.isSymbolicLink(destination.toPath())) continue;
            String linkTarget = FileUtils.readSymlink(destination);
            if (linkTarget == null || !linkTarget.contains("/opt/wine/")) continue;

            File source = new File(sourceDir, destination.getName());
            if (!source.isFile()) {
                // Modern Proton intentionally no longer ships every DLL from the
                // old /opt/wine runtime. Leaving an old link here makes Wine see
                // a non-existent override instead of its current built-in DLL.
                if (FileUtils.delete(destination)) {
                    Log.i("ImageFSLegacyMigrator", "Removed obsolete DLL link: " + destination.getName());
                } else {
                    Log.e("ImageFSLegacyMigrator", "Failed to remove obsolete DLL link: " + destination.getAbsolutePath());
                }
                continue;
            }
            if (!FileUtils.delete(destination) || !FileUtils.symlink(source, destination)) {
                Log.e("ImageFSLegacyMigrator", "Failed to repair DLL link: " + destination.getAbsolutePath());
                continue;
            }
            Log.i("ImageFSLegacyMigrator", "Repaired DLL link: " + destination.getName());
        }
    }

    /**
     * Before deleting legacy files/imagefs, preserve /home contents by moving them into
     * files/imagefs_shared/home so first-run sync can reuse xuser/.wine safely.
     */
    private static boolean migrateLegacyHomeToShared(Context context, File legacyImageFsRoot) {
        File legacyHome = new File(legacyImageFsRoot, "home");
        File sharedHomeRoot = new File(ImageFs.getImageFsSharedDir(context), "home");

        if (FileUtils.isSymlink(legacyHome)) {
            // Already migrated: /imagefs/home is a symlink to imagefs_shared/home.
            return true;
        }

        if (!legacyHome.exists() || !legacyHome.isDirectory()) {
            // No need to migrate.
            return true;
        }

        if (sharedHomeRoot.exists()) {
            Log.w("ImageFSLegacyMigrator", "Shared home already exists; overwriting with legacy home migration.");
            FileUtils.delete(sharedHomeRoot);
        }

        if (!legacyHome.renameTo(sharedHomeRoot)) {
            Log.w("ImageFSLegacyMigrator", "Direct move failed for legacy home; falling back to copy+delete.");
            boolean copied = FileUtils.copy(legacyHome, sharedHomeRoot);
            if (copied) {
                FileUtils.delete(legacyHome);
                Log.i("ImageFSLegacyMigrator", "Migrated legacy home via copy+delete to: " + sharedHomeRoot.getAbsolutePath());
                return true;
            } else {
                Log.w("ImageFSLegacyMigrator", "Failed to migrate legacy home directory: " + legacyHome.getAbsolutePath());
                return false;
            }
        } else {
            Log.i("ImageFSLegacyMigrator", "Migrated legacy home via direct move to: " + sharedHomeRoot.getAbsolutePath());
            return true;
        }
    }

    /**
     * Before deleting legacy opt/proton-<version> directories, preserve them by moving them into
     * files/imagefs_shared/proton so they can be symlinked.
     */
    private static boolean migrateLegacyProtonToShared(Context context, File legacyImageFsRoot) {
        File optDir = new File(legacyImageFsRoot, "opt");
        File[] optEntries = optDir.listFiles();
        if (optEntries == null) {
            return true;
        }

        for (File entry : optEntries) {
            if (!entry.isDirectory() || FileUtils.isSymlink(entry) || !entry.getName().startsWith("proton-")) {
                continue;
            }

            File sharedProtonDir = new File(ImageFs.getSharedProtonDir(context), entry.getName());
            if (sharedProtonDir.exists()) {
                Log.w("ImageFSLegacyMigrator", "Shared Proton already exists; removing duplicate legacy opt entry: " + entry.getName());
                if (!FileUtils.delete(entry)) {
                    Log.w("ImageFSLegacyMigrator", "Failed to remove duplicate legacy Proton directory: " + entry.getAbsolutePath());
                    return false;
                }
                continue;
            }

            if (!entry.renameTo(sharedProtonDir)) {
                Log.w("ImageFSLegacyMigrator", "Direct move failed for Proton " + entry.getName() + "; falling back to copy+delete.");
                boolean copied = FileUtils.copy(entry, sharedProtonDir);
                if (copied) {
                    FileUtils.delete(entry);
                    Log.i("ImageFSLegacyMigrator", "Migrated Proton via copy+delete to: " + sharedProtonDir.getAbsolutePath());
                    continue;
                }
                Log.w("ImageFSLegacyMigrator", "Failed to migrate Proton directory: " + entry.getAbsolutePath());
                return false;
            }

            Log.i("ImageFSLegacyMigrator", "Migrated Proton via direct move to: " + sharedProtonDir.getAbsolutePath());
        }

        return true;
    }
}
