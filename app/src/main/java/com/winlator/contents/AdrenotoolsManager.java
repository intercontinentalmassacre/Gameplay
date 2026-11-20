package com.winlator.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.core.DefaultVersion;
import com.winlator.core.KeyValueSet;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUInformation;
import com.winlator.core.TarCompressorUtils;
import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    public static final String SYSTEM_DRIVER_ID = "System";
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 2048;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private static final String STAGING_PREFIX = ".driver-install-";

    private File adrenotoolsContentDir;
    private Context mContext;

    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }

    public String getLibraryName(String adrenoToolsDriverId) {
        return getMetadataValue(adrenoToolsDriverId, "libraryName");
    }

    public String getDriverName(String adrenoToolsDriverId) {
        return getMetadataValue(adrenoToolsDriverId, "name");
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        return getMetadataValue(adrenoToolsDriverId, "driverVersion");
    }

    private String getMetadataValue(String driverId, String key) {
        if (!isSafeFileName(driverId)) return "";
        try {
            return readMetadata(new File(adrenotoolsContentDir, driverId)).optString(key, "");
        } catch (IOException | JSONException e) {
            return "";
        }
    }

    private void reloadContainers(String adrenoToolsDriverId) {
        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            KeyValueSet config = new KeyValueSet(container.getGraphicsDriverConfig());
            Log.d("AdrenotoolsManager", "Checking if container driver version " + config.get("version") + " matches " + getDriverName(adrenoToolsDriverId));
            if (config.get("version").equals(adrenoToolsDriverId)) {
                Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
                config.put("version", DefaultVersion.WRAPPER);
                container.setGraphicsDriverConfig(config.toString());
                container.saveData();
            }
        }
    }

    public void removeDriver(String adrenoToolsDriverId) {
        if (!isSafeFileName(adrenoToolsDriverId)) return;
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        File[] entries = adrenotoolsContentDir.listFiles(File::isDirectory);
        if (entries == null) return driversList;
        for (File f : entries) {
            if (f.getName().startsWith(STAGING_PREFIX)) continue;
            boolean fromResources = isFromResources("graphics_driver/adrenotools-" + f.getName() + ".tzst");
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }

    private boolean isFromResources(String driver) {
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;

        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }

        return isFromResources;
    }

    private boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            dst.delete();

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }

    public String installDriver(Uri driverUri) {
        try {
            InputStream input = mContext.getContentResolver().openInputStream(driverUri);
            if (input == null) return "";
            return installDriverArchive(input, adrenotoolsContentDir);
        } catch (IOException | JSONException | SecurityException e) {
            Log.w("AdrenotoolsManager", "Could not install graphics driver", e);
            return "";
        }
    }

    static String installDriverArchive(InputStream input, File driverDir) throws IOException, JSONException {
        Path destination = driverDir.toPath().toAbsolutePath().normalize();
        Path staging = null;
        try {
            try (ZipInputStream archive = new ZipInputStream(input)) {
                Files.createDirectories(destination);
                staging = Files.createTempDirectory(destination, STAGING_PREFIX);
                extractDriverArchive(archive, staging);
            }
            File packageDir = findPackageDirectory(staging.toFile());
            JSONObject metadata = readMetadata(packageDir);
            String id = metadata.getString("name").trim();
            String library = metadata.getString("libraryName");
            if (!isSafeFileName(id) || id.startsWith(STAGING_PREFIX) || id.equalsIgnoreCase("System") ||
                    id.indexOf(',') >= 0 || id.indexOf(';') >= 0 || id.indexOf('=') >= 0) {
                throw new IOException("Invalid graphics driver name");
            }
            if (!isSafeFileName(library) || !isArm64SharedLibrary(new File(packageDir, library))) {
                throw new IOException("Graphics driver package is missing its ARM64 Vulkan library");
            }
            Files.move(packageDir.toPath(), destination.resolve(id));
            return id;
        } finally {
            if (staging != null && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) FileUtils.delete(staging.toFile());
        }
    }

    private static void extractDriverArchive(ZipInputStream archive, Path staging) throws IOException {
        Set<Path> paths = new HashSet<>();
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        for (ZipEntry entry; (entry = archive.getNextEntry()) != null; archive.closeEntry()) {
            String name = entry.getName();
            if (name.isEmpty() || name.startsWith("/") || name.contains("\\") ||
                    name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:.*")) {
                throw new IOException("Invalid graphics driver archive path");
            }
            Path path = staging.resolve(name).normalize();
            if (!path.startsWith(staging) || !paths.add(path) || paths.size() > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("Unsafe or duplicate graphics driver archive entry");
            }
            if (entry.isDirectory()) {
                Files.createDirectories(path);
                if (archive.read() != -1) throw new IOException("Graphics driver directory entry has data");
                continue;
            }
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                for (int count; (count = archive.read(buffer)) != -1;) {
                    total += count;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("Graphics driver package is too large");
                    output.write(buffer, 0, count);
                }
            }
        }
    }

    private static File findPackageDirectory(File staging) throws IOException {
        if (new File(staging, "meta.json").isFile()) return staging;
        File[] wrappers = staging.listFiles(file -> file.isDirectory() && new File(file, "meta.json").isFile());
        if (wrappers == null || wrappers.length != 1) throw new IOException("Expected one graphics driver meta.json");
        return wrappers[0];
    }

    private static JSONObject readMetadata(File folder) throws IOException, JSONException {
        File file = new File(folder, "meta.json");
        if (!file.isFile() || file.length() > MAX_METADATA_BYTES) throw new IOException("Invalid graphics driver meta.json");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static boolean isSafeFileName(String name) {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..") ||
                name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0) return false;
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) return false;
        }
        return true;
    }

    private static boolean isArm64SharedLibrary(File file) throws IOException {
        if (!file.isFile() || file.length() < 64) return false;
        byte[] header = new byte[20];
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file.toPath()))) {
            input.readFully(header);
        }
        return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' &&
                header[4] == 2 && header[5] == 1 && header[6] == 1 &&
                header[16] == 3 && header[17] == 0 && (header[18] & 0xff) == 183 && header[19] == 0;
    }

    public boolean setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        if (!isSafeFileName(adrenotoolsDriverId) || SYSTEM_DRIVER_ID.equalsIgnoreCase(adrenotoolsDriverId)) return false;
        File driverDir = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        String libraryName = getLibraryName(adrenotoolsDriverId);
        if (!isUsableDriverLibrary(driverDir, libraryName)) {
            String asset = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
            if (!isFromResources(asset) || !extractDriverFromResources(adrenotoolsDriverId)) return false;
            libraryName = getLibraryName(adrenotoolsDriverId);
            if (!isUsableDriverLibrary(driverDir, libraryName)) return false;
        }

        String driverPath = driverDir.getAbsolutePath() + "/";
        envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
        envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
        envVars.put("ADRENOTOOLS_DRIVER_NAME", libraryName);
        if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion(mContext).contains("512.530")) {
            Log.d("AdrenotoolsManager", "Patching v762 driver for stock v530");
            FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 3);
        } else if (adrenotoolsDriverId.contains("v762") && GPUInformation.getVersion(mContext).contains("512.502")) {
            Log.d("AdrenotoolsManager", "Patching v762 driver for stock v502");
            FileUtils.writeToBinaryFile(driverPath + "notadreno_utils.so", 0x2680, 2);
        }
        return true;
    }

    private static boolean isUsableDriverLibrary(File driverDir, String libraryName) {
        if (!isSafeFileName(libraryName)) return false;
        try {
            return isArm64SharedLibrary(new File(driverDir, libraryName));
        } catch (IOException e) {
            return false;
        }
    }
}
