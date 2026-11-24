package com.winlator.container;

import android.content.Context;
import android.util.Log;

import app.gamenative.PrefManager;
import com.winlator.contents.ContentsManager;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class ContainerOverlayMigrator {
    private static final String PENDING = "overlayMigrationPending";
    private static final String[] ROOTS = {"windows", "Program Files", "Program Files (x86)", "ProgramData"};
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private ContainerOverlayMigrator() {}

    public static synchronized void migrateIfNeeded(Context context, ContentsManager contents, Container container) throws IOException {
        boolean pending = !container.getExtra(PENDING).isEmpty();
        String storage = container.getExtra("downloadPrefixStorage");
        boolean enabled = "shared".equals(storage) || (!"private".equals(storage) && PrefManager.INSTANCE.getMigrateExistingPrefixes());
        if (!ContainerOverlay.isEligible(container) || (!pending && !enabled)) return;
        if (container.isOverlay() && !pending) {
            if (new File(container.getBasePrefix()).isDirectory())
                SharedWineComponents.migrate(container, new ContainerManager(context).getContainers());
            return;
        }
        File upper = ContainerFiles.upperDir(container);
        if (!Files.isDirectory(upper.toPath(), NO_FOLLOW) ||
                !Files.isDirectory(new File(upper, "drive_c").toPath(), NO_FOLLOW) ||
                !new File(upper, "user.reg").isFile() || !ContainerOverlay.bionicLibFile(context).isFile()) return;
        ContainerOverlay.splash("Preparing shared Wine prefix...");
        File base = BasePrefix.ensure(context, contents, container.getWineVersion());
        if (base == null) return;
        migrate(container, base);
        SharedWineComponents.migrate(container, new ContainerManager(context).getContainers());
    }

    static void migrate(Container container, File base) throws IOException {
        File upper = ContainerFiles.upperDir(container);
        String path = ContainerOverlay.canonicalHostPath(base);
        if (!container.isOverlay()) {
            // A full prefix may have deliberately removed files from the Wine template.
            markMissing(upper, base, "");
            if (!ContainerFiles.markOpaque(upper, ContainerFiles.DOSDEVICES)) throw new IOException("Cannot preserve Wine drive mappings");
            // Publish the working overlay before unlinking anything. A killed process can resume the scan.
            persist(container, path, true);
        }
        if (!path.equals(container.getBasePrefix()) || container.getExtra(PENDING).isEmpty()) return;
        byte[] left = new byte[64 * 1024];
        byte[] right = new byte[left.length];
        for (String root : ROOTS) {
            String rel = "drive_c/" + root;
            prune(upper, base, rel, left, right);
        }
        File cache = new File(container.getRootDir(), ".cache");
        if (Files.isDirectory(cache.toPath(), NO_FOLLOW) && !ContainerFiles.deleteRecursively(new File(cache, "original_dlls")))
            throw new IOException("Cannot remove migrated DLL backups");
        persist(container, path, false);
        Log.i("ContainerOverlayMigrator", "Migrated " + container.id + " to " + path);
    }

    private static File[] children(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Cannot list " + dir);
        return files;
    }

    private static void markMissing(File upper, File dir, String parent) throws IOException {
        for (File file : children(dir)) {
            String rel = parent.isEmpty() ? file.getName() : parent + "/" + file.getName();
            if (rel.equals(ContainerFiles.DOSDEVICES) || rel.equals(ContainerOverlay.OVERLAY_DIR)) continue;
            Path dst = new File(upper, rel).toPath();
            if (Files.notExists(dst, NO_FOLLOW)) {
                if (!ContainerFiles.setWhiteout(upper, rel)) throw new IOException("Cannot preserve removed file " + rel);
            } else if (Files.isDirectory(dst, NO_FOLLOW) && Files.isDirectory(file.toPath(), NO_FOLLOW)) {
                markMissing(upper, file, rel);
            }
        }
    }

    private static void prune(File upper, File lower, String rel, byte[] left, byte[] right) throws IOException {
        File dst = new File(upper, rel);
        File src = new File(lower, rel);
        if (Files.isDirectory(src.toPath(), NO_FOLLOW) && Files.isDirectory(dst.toPath(), NO_FOLLOW)) {
            for (File file : children(src)) prune(upper, lower, rel + "/" + file.getName(), left, right);
        } else if (Files.isRegularFile(src.toPath(), NO_FOLLOW) && Files.isRegularFile(dst.toPath(), NO_FOLLOW)
                && !ContainerFiles.isAppManaged(rel) && !ContainerFiles.isHidden(upper, rel)
                && dst.length() == src.length() && identical(dst, src, left, right)) {
            Files.delete(dst.toPath());
        }
    }

    private static boolean identical(File a, File b, byte[] left, byte[] right) throws IOException {
        try (FileInputStream first = new FileInputStream(a);
             DataInputStream second = new DataInputStream(new FileInputStream(b))) {
            int count;
            while ((count = first.read(left)) != -1) {
                second.readFully(right, 0, count);
                if (count == left.length) {
                    if (!Arrays.equals(left, right)) return false;
                } else {
                    for (int i = 0; i < count; i++) if (left[i] != right[i]) return false;
                }
            }
            return second.read() == -1;
        }
    }

    private static void persist(Container container, String path, boolean pending) throws IOException {
        Path config = container.getConfigFile().toPath();
        Path temp = Files.createTempFile(config.getParent(), ".migrate-", ".json");
        try {
            JSONObject data = new JSONObject(new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
            JSONObject extra = data.optJSONObject("extraData");
            if (extra == null) extra = new JSONObject();
            extra.put("basePrefix", path);
            extra.put(PENDING, pending ? "1" : null);
            data.put("extraData", extra);
            try (FileOutputStream out = new FileOutputStream(temp.toFile())) {
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            Files.move(temp, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            container.setBasePrefix(path);
            container.putExtra(PENDING, pending ? "1" : null);
        } catch (JSONException e) {
            throw new IOException("Cannot update overlay configuration", e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
