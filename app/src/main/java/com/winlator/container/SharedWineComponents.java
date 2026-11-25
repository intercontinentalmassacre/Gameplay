package com.winlator.container;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

public final class SharedWineComponents {
    static final String TREE = ".gnoverlay/components";
    private static final Pattern DIRECTX = Pattern.compile("(d3dx(9|10|11)_|d3dcsx_|d3dcompiler_)[0-9]+\\.dll", Pattern.CASE_INSENSITIVE);
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    private SharedWineComponents() {}

    private static StructStat stat(Path path) throws IOException {
        try { return Os.lstat(path.toString()); }
        catch (ErrnoException e) { throw new IOException(e); }
    }

    private static String digest(Path path, byte[] buffer) throws IOException {
        MessageDigest hash;
        try { hash = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try (InputStream stream = Files.newInputStream(path)) {
            int n;
            while ((n = stream.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash.digest()) hex.append(Character.forDigit((b & 255) >>> 4, 16)).append(Character.forDigit(b & 15, 16));
        return hex.toString();
    }

    private static void collect(Path upper, Map<Long, List<Path>> sizes) throws IOException {
        Path windows = upper.resolve("drive_c/windows");
        if (!windows.toFile().getCanonicalFile().toPath().equals(windows)) return;
        for (String dir : Arrays.asList("Installer", "mono", "system32", "syswow64")) {
            Path root = windows.resolve(dir);
            if (!Files.isDirectory(root, NOFOLLOW)) continue;
            Files.walkFileTree(root, Collections.emptySet(), dir.equals("mono") ? Integer.MAX_VALUE : 1,
                    new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String name = file.getFileName().toString();
                    boolean allowed = dir.equals("mono") || (dir.equals("Installer")
                            ? name.toLowerCase(Locale.ROOT).endsWith(".msi") : DIRECTX.matcher(name).matches());
                    if (allowed && attrs.isRegularFile() && attrs.size() > 0 && stat(file).st_nlink == 1)
                        sizes.computeIfAbsent(attrs.size(), key -> new ArrayList<>()).add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    public static synchronized void shareDxvk(Container current, List<Container> containers) {
        if (!current.isOverlay()) return;
        try {
            Path upper = ContainerFiles.upperDir(current).getCanonicalFile().toPath();
            Map<Long, List<Path>> sizes = new HashMap<>();
            for (Container peer : containers) {
                if (!peer.isOverlay()) continue;
                Path windows = ContainerFiles.upperDir(peer).getCanonicalFile().toPath().resolve("drive_c/windows");
                for (String dir : Arrays.asList("system32", "syswow64")) {
                    Path arch = windows.resolve(dir);
                    if (!arch.toFile().getCanonicalFile().toPath().equals(arch)) continue;
                    for (String dll : Arrays.asList("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")) {
                        Path file = arch.resolve(dll);
                        if (!Files.isRegularFile(file, NOFOLLOW)) continue;
                        StructStat info = stat(file);
                        if (info.st_nlink == 1 && info.st_size > 0)
                            sizes.computeIfAbsent(info.st_size, key -> new ArrayList<>()).add(file);
                    }
                }
            }
            shareMatches(current, upper, dxvkPool(current), sizes);
        } catch (IOException | UnsupportedOperationException e) {
            Log.w("SharedWineComponents", "Keeping remaining DXVK files private for " + current.id, e);
        }
    }

    private static Path dxvkPool(Container container) {
        return container.getRootDir().getParentFile().toPath().resolve(".dxvk-components");
    }

    static synchronized void migrate(Container current, List<Container> containers) {
        try { shareInstalled(current, containers); }
        catch (IOException | UnsupportedOperationException e) {
            Log.w("SharedWineComponents", "Keeping remaining files private for " + current.id, e);
        }
    }

    private static void shareInstalled(Container current, List<Container> containers) throws IOException {
        Path upper = ContainerFiles.upperDir(current).getCanonicalFile().toPath();
        Path pool = new File(current.getBasePrefix()).toPath().getParent().resolve("components");
        Map<Long, List<Path>> sizes = new HashMap<>();
        collect(upper, sizes);
        if (sizes.isEmpty()) return;
        for (Container peer : containers) {
            if (!peer.id.equals(current.id) && peer.isOverlay() && peer.getBasePrefix().equals(current.getBasePrefix()))
                collect(ContainerFiles.upperDir(peer).getCanonicalFile().toPath(), sizes);
        }
        shareMatches(current, upper, pool, sizes);
    }

    private static void shareMatches(Container current, Path upper, Path pool, Map<Long, List<Path>> sizes) throws IOException {
        if (sizes.isEmpty()) return;
        Files.createDirectories(pool);
        byte[] buffer = new byte[65536];
        int count = 0;
        for (List<Path> candidates : sizes.values()) {
            if (candidates.stream().noneMatch(file -> file.startsWith(upper))) continue;
            Map<String, List<Path>> matches = new HashMap<>();
            for (Path file : candidates) {
                String key = digest(file, buffer) + "-" + Integer.toOctalString(stat(file).st_mode & 0777);
                matches.computeIfAbsent(key, ignored -> new ArrayList<>()).add(file);
            }
            for (Map.Entry<String, List<Path>> match : matches.entrySet()) {
                Path blob = pool.resolve(match.getKey());
                List<Path> files = match.getValue();
                if (files.size() < 2 && !Files.isRegularFile(blob, NOFOLLOW)) continue;
                for (Path file : files) {
                    if (!file.startsWith(upper)) continue;
                    String rel = upper.relativize(file).toString();
                    if (ContainerFiles.isHidden(upper.toFile(), rel)) continue;
                    if (!Files.exists(blob, NOFOLLOW)) {
                        Path tmp = Files.createTempFile(pool, ".copy-", ".tmp");
                        try {
                            Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                            if (!match.getKey().startsWith(digest(tmp, buffer) + "-")) throw new IOException("Component changed: " + file);
                            Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE);
                        } finally { Files.deleteIfExists(tmp); }
                    }
                    Path installed = upper.resolve(TREE).resolve(rel);
                    Files.createDirectories(installed.getParent());
                    // Publish the private lower name before removing the upper. A retry is safe at either step.
                    if (!Files.isRegularFile(installed, NOFOLLOW) || !Files.isSameFile(installed, blob)) {
                        Path link = installed.resolveSibling(installed.getFileName() + ".gnoverlay-tmp");
                        Files.deleteIfExists(link);
                        Files.createLink(link, blob);
                        Files.move(link, installed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.delete(file);
                    count++;
                }
            }
        }
        collectUnused(pool);
        Log.i("SharedWineComponents", "Shared " + count + " files for " + current.id);
    }

    static synchronized void collectUnused(Container container) {
        if (!container.isOverlay()) return;
        collectUnused(new File(container.getBasePrefix()).toPath().getParent().resolve("components"));
        collectUnused(dxvkPool(container));
    }

    private static void collectUnused(Path pool) {
        if (!Files.isDirectory(pool, NOFOLLOW)) return;
        try (DirectoryStream<Path> blobs = Files.newDirectoryStream(pool)) {
            for (Path blob : blobs) {
                if (Files.isRegularFile(blob, NOFOLLOW) && stat(blob).st_nlink == 1) Files.delete(blob);
            }
        } catch (IOException e) {
            Log.w("SharedWineComponents", "Could not reclaim unused components", e);
        }
    }
}
