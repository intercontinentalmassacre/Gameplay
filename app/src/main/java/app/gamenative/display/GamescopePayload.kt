package app.gamenative.display

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import com.winlator.core.TarCompressorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.Properties
import org.json.JSONObject
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class GamescopeRuntime(val directory: File, val icdOverride: File? = null)

enum class GamescopeExperiment { GL, CAP }

/** Versioned gamescope payload (gamescope, XWayland, scaling clients), fetched on the first gamescope launch. */
object GamescopePayload {
    const val VERSION = "20261116.1"
    const val ARCHIVE = "gamescope-gamenative-$VERSION.tzst"
    private const val ARCHIVE_SHA256 = "5e2bf27ce8dda593297872fe5e4975112cc8d8d2b7d06fbb1149450d72ce08d9"
    private const val STAMP = ".payload.version"
    private const val LOCATION_STAMP = ".xwayland.prefix"
    private const val XWAYLAND_BUILD_PREFIX = "/data/user/0/app.gamenative/files/gamescope"
    const val EXPERIMENTAL_CONTAINER_EXTRA = "gamescopeExperimental"
    private const val EXPERIMENTAL_VERSION = "20261121.1"
    private const val EXPERIMENTAL_DIR = "gamescope-experimental"
    private const val EXPERIMENTAL_ARCHIVE = "$EXPERIMENTAL_DIR/gamescope-gamenative-$EXPERIMENTAL_VERSION.tzst"
    private const val CAP_SHIM = "libadrenotools_gamescope-pr71.so"
    private const val CAP_SHIM_SHA = "2b067c0c93272ce9901f3e2eff34fc127ffbc41e64f24e7e3eefc23efe4ae0d1"
    private const val STABLE_HOST_SHA = "6441f18a5e28ffccf5938e31b74d233409450d6c5f8d0a5d8c443fb025a9d7eb"
    private val installMutex = Mutex()

    private val requiredPaths = listOf(
        "bin/gamescope",
        "bin/Xwayland",
        "bin/gn-activate.exe",
        "bin/xkbcomp",
        "lib/libgndrm_shim.so",
        "share/vulkan/icd.d/adrenotools_gamescope.aarch64.json",
    )

    fun directory(filesDir: File): File = GamescopeSettings.gamescopeDir(filesDir)

    @JvmStatic
    fun isExperimentalDirectory(directory: File): Boolean = directory.name == EXPERIMENTAL_DIR

    private fun experimentalManifest(context: Context): Properties? = runCatching {
        val spec = Properties().apply {
            context.assets.open("$EXPERIMENTAL_DIR/bundle.properties").use { load(it) }
        }
        val digest = Regex("[a-f0-9]{64}")
        check(spec.getProperty("version") == EXPERIMENTAL_VERSION)
        check(digest.matches(spec.getProperty("archiveSha256").orEmpty()))
        check(digest.matches(spec.getProperty("hostSha256").orEmpty()))
        context.assets.open(EXPERIMENTAL_ARCHIVE).close()
        spec
    }.getOrNull()

    suspend fun availableExperiment(context: Context): GamescopeExperiment? = withContext(Dispatchers.IO) {
        val full = if (supportsExperimentalGl()) experimentalManifest(context) else null
        when {
            full != null && compatibleHost(context, full.getProperty("hostSha256")) -> GamescopeExperiment.GL
            hasExperimentalCap(context) && compatibleHost(context, STABLE_HOST_SHA) -> GamescopeExperiment.CAP
            else -> null
        }
    }

    // Guest GLX/kopper needs the Xwayland glamor/DRI3 path. The launcher disables glamor off KGSL.
    private fun supportsExperimentalGl() = File("/dev/kgsl-3d0").exists()

    fun hasExperimentalCap(context: Context): Boolean = runCatching {
        context.assets.open("$EXPERIMENTAL_DIR/$CAP_SHIM").close()
        true
    }.getOrDefault(false)

    suspend fun ensureExperimentalCap(context: Context): File? = withContext(Dispatchers.IO) {
        if (!hasExperimentalCap(context)) return@withContext null
        check(compatibleHost(context, STABLE_HOST_SHA)) { "Experimental cap shim expects the stable Android DRM daemon" }
        installMutex.withLock {
            val dir = File(context.filesDir, "gamescope-cap-pr71")
            check(dir.isDirectory || dir.mkdirs()) { "Could not create the experimental cap directory" }
            val shim = File(dir, CAP_SHIM)
            if (!shim.isFile || !hasExpectedDigest(shim, CAP_SHIM_SHA)) {
                val tmp = File.createTempFile("cap-shim-", ".so", dir)
                try {
                    context.assets.open("$EXPERIMENTAL_DIR/$CAP_SHIM").use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    check(hasExpectedDigest(tmp, CAP_SHIM_SHA)) { "Experimental cap shim checksum mismatch" }
                    Files.move(tmp.toPath(), shim.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    tmp.delete()
                }
            }
            val icd = File(dir, "adrenotools_gamescope.aarch64.json")
            val json = JSONObject().put("file_format_version", "1.0.1").put(
                "ICD",
                JSONObject().put("api_version", "1.3.0")
                    .put("library_arch", "64")
                    .put("library_path", shim.absolutePath),
            )
            icd.writeText(json.toString())
            icd
        }
    }

    private fun compatibleHost(context: Context, expected: String): Boolean {
        val library = File(context.applicationInfo.nativeLibraryDir, "libgamenative_drm.so")
        return library.isFile && hasExpectedDigest(library, expected)
    }

    suspend fun ensureExperimental(context: Context): File? = withContext(Dispatchers.IO) {
        if (!supportsExperimentalGl()) return@withContext null
        val spec = experimentalManifest(context) ?: return@withContext null
        val version = "$EXPERIMENTAL_VERSION:${spec.getProperty("archiveSha256")}"
        check(compatibleHost(context, spec.getProperty("hostSha256"))) {
            "Experimental Gamescope bundle expects a different Android DRM daemon"
        }
        val target = File(context.filesDir, EXPERIMENTAL_DIR)
        val alias = File(context.filesDir, "gsx").absoluteFile
        fun validTree(dir: File): Boolean = hasRequiredFiles(dir) &&
            listOf(
                "bin/gamescopereaper", "bin/gn-scaling-mode", "lib/libadrenotools_gamescope.so",
                "lib/gl/libGL.so.1", "lib/gl/libEGL.so.1", "lib/gl/libGLESv2.so.2",
                "lib/gl/libgbm.so.1", "lib/gl/libgallium-26.2.6.so", "lib/gl/dri/zink_dri.so",
                "lib/gl/gbm/dri_gbm.so",
            ).all { File(dir, it).isFile }
        installMutex.withLock {
            val correctLink = runCatching {
                Files.readSymbolicLink(alias.toPath()).toString() == target.name
            }.getOrDefault(false)
            if (correctLink && validTree(target) && runCatching {
                    File(target, STAMP).readText() == version &&
                        File(target, LOCATION_STAMP).readText() == alias.path
                }.getOrDefault(false)
            ) return@withLock target

            val archive = File.createTempFile("gamescope-experimental-", ".tzst", context.cacheDir)
            val tempDir = File(context.filesDir, "$EXPERIMENTAL_DIR.tmp")
            try {
                context.assets.open(EXPERIMENTAL_ARCHIVE).use { input ->
                    archive.outputStream().use { input.copyTo(it) }
                }
                check(hasExpectedDigest(archive, spec.getProperty("archiveSha256"))) {
                    "Experimental Gamescope bundle checksum mismatch"
                }
                AssetUtils.deleteTree(tempDir)
                check(tempDir.mkdir())
                check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, tempDir) && validTree(tempDir)) {
                    "Experimental Gamescope bundle is incomplete"
                }
                relocateXwaylandPaths(File(tempDir, "bin/Xwayland"), alias.path, expectedPaths = 8)
                relocateXwaylandPaths(File(tempDir, "bin/xkbcomp"), alias.path, expectedPaths = 1)
                if (!correctLink) {
                    check(!Files.exists(alias.toPath(), NOFOLLOW_LINKS)) { "Experimental Gamescope alias is in use" }
                    Files.createSymbolicLink(alias.toPath(), File(target.name).toPath())
                }
                File(tempDir, STAMP).writeText(version)
                File(tempDir, LOCATION_STAMP).writeText(alias.path)
                AssetUtils.deleteTree(target)
                check(tempDir.renameTo(target)) { "Could not install the experimental Gamescope bundle" }
                target
            } finally {
                archive.delete()
                AssetUtils.deleteTree(tempDir)
            }
        }
    }

    fun isReady(filesDir: File): Boolean {
        val dir = directory(filesDir)
        val prefix = xwaylandPrefix(filesDir)
        return hasRequiredFiles(dir) &&
            runCatching {
                File(dir, STAMP).readText().trim() == VERSION &&
                    File(dir, LOCATION_STAMP).readText() == prefix.path &&
                    Files.readSymbolicLink(prefix.toPath()).toString() == dir.name
            }.getOrDefault(false)
    }

    // Keep the relocated absolute prefix short enough for XWayland's compiled-in C strings.
    private fun xwaylandPrefix(filesDir: File): File = File(filesDir, "gsc").absoluteFile

    private fun hasRequiredFiles(dir: File): Boolean =
        requiredPaths.all { File(dir, it).isFile }

    suspend fun ensure(context: Context, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        installMutex.withLock {
            if (isReady(filesDir)) return@withLock directory(filesDir)
            val archive = File(filesDir, ARCHIVE)
            if (!archive.isFile || !hasExpectedDigest(archive)) {
                archive.delete()
                if (copyFromAssets(context, archive)) {
                    onProgress(1.0f)
                } else {
                    try {
                        SteamService.fetchFileWithFallback(ARCHIVE, archive, context, onProgress)
                    } catch (e: Exception) {
                        archive.delete()
                        throw e
                    }
                }
                if (!hasExpectedDigest(archive)) {
                    archive.delete()
                    error("Gamescope payload checksum mismatch for $ARCHIVE")
                }
            }
            val prefix = xwaylandPrefix(filesDir)
            val prefixPath = prefix.toPath()
            val target = directory(filesDir)
            if (!Files.isSymbolicLink(prefixPath) ||
                Files.readSymbolicLink(prefixPath).toString() != target.name
            ) {
                check(!Files.exists(prefixPath, NOFOLLOW_LINKS)) { "Gamescope runtime alias is in use: $prefix" }
                Files.createSymbolicLink(prefixPath, File(target.name).toPath())
            }
            install(archive, filesDir) { staged ->
                relocateXwaylandPaths(File(staged, "bin/Xwayland"), prefix.path, expectedPaths = 8)
                relocateXwaylandPaths(File(staged, "bin/xkbcomp"), prefix.path, expectedPaths = 1)
                File(staged, LOCATION_STAMP).writeText(prefix.path)
            }
        }
    }

    /** Relocate an authenticated payload without changing ELF offsets or executable modes. */
    private fun relocateXwaylandPaths(executable: File, prefix: String, expectedPaths: Int) {
        check(prefix.length <= XWAYLAND_BUILD_PREFIX.length) { "Gamescope runtime path is too long: $prefix" }
        val bytes = executable.readBytes()
        val original = bytes.toString(Charsets.ISO_8859_1)
        var offset = original.indexOf(XWAYLAND_BUILD_PREFIX)
        var count = 0
        while (offset >= 0) {
            val suffixStart = offset + XWAYLAND_BUILD_PREFIX.length
            val end = original.indexOf('\u0000', suffixStart)
            check(end >= suffixStart) { "Invalid Gamescope runtime path in ${executable.name}" }
            val relocated = (prefix + original.substring(suffixStart, end)).toByteArray(Charsets.ISO_8859_1)
            relocated.copyInto(bytes, offset)
            bytes.fill(0, offset + relocated.size, end)
            offset = original.indexOf(XWAYLAND_BUILD_PREFIX, end + 1)
            count++
        }
        check(count == expectedPaths) { "Unexpected Gamescope runtime paths in ${executable.name}" }
        executable.writeBytes(bytes)
    }

    private fun hasExpectedDigest(archive: File, expected: String = ARCHIVE_SHA256): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    /** Whether this APK carries the payload, and if so, copy it out to [dest]. */
    private fun copyFromAssets(context: Context, dest: File): Boolean {
        val assets = context.assets
        return runCatching {
            assets.open(ARCHIVE).use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        }
            .onFailure {
                if (it !is java.io.FileNotFoundException) Timber.w(it, "Failed to copy $ARCHIVE from assets")
                dest.delete()
            }
            .isSuccess
    }

    @Synchronized
    internal fun install(archive: File, filesDir: File, configure: (File) -> Unit = {}): File {
        val target = directory(filesDir)
        if (isReady(filesDir)) return target
        val tempDir = File(target.parentFile, "${target.name}.tmp")
        AssetUtils.deleteTree(tempDir)
        tempDir.mkdirs()
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, tempDir) ||
            !hasRequiredFiles(tempDir)
        ) {
            AssetUtils.deleteTree(tempDir)
            archive.delete()
            error("Failed to extract $ARCHIVE")
        }
        try {
            configure(tempDir)
            File(tempDir, STAMP).writeText(VERSION)
            AssetUtils.deleteTree(target)
            check(tempDir.renameTo(target)) { "Failed to promote $ARCHIVE" }
        } finally {
            AssetUtils.deleteTree(tempDir)
        }
        archive.delete()
        Timber.i("Gamescope payload $VERSION ready at ${target.absolutePath}")
        return target
    }
}
