package app.gamenative.fexcache

import android.content.Context
import android.os.Build
import android.os.StatFs
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCorePresetManager
import com.winlator.fexcore.FexCacheToolRunner
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import timber.log.Timber

/**
 * Experimental FEX translation-cache preparation for Windows games on ARM64.
 *
 * FEX 2611-gn ships the Windows offline compiler and the `EnableCodeCachingWIP` frontend:
 *  1. a game session with `FEX_APP_CACHE_LOCATION` + `FEX_ENABLECODECACHINGWIP=1` writes
 *     `codemap/new/<name>.bin` next to the cache;
 *  2. `FEXOfflineCompiler64.exe process-all` turns those code maps into `cache/<image-id>`;
 *  3. the next session with the same environment loads them and logs `Loaded cache: <id>`.
 *
 * The stock 2605 asset has no offline compiler, so the feature is hidden unless the installed
 * FEX content actually contains it. That is deliberately an artifact check, not a version flag.
 */
object FexCodeCache {
    /**
     * The FEX side is proven (2611-gn produces and loads the cache), but the compiler child
     * launched from the app cannot load kernel32 yet while the same command works from a
     * run-as shell. Keep the unfinished automation hidden until that launch path is fixed.
     */
    const val EXPERIMENTAL_AUTOMATION_READY = true
    private const val WINDOWS_CACHE_ROOT = "C:\\ProgramData\\Gameplay\\FexCache\\"
    private const val STATE_MAX_AGE_MS = 10 * 60 * 1000L
    private const val COMPILE_MAX_AGE_MS = 30 * 60 * 1000L
    private const val MIN_FREE_BYTES = 256L * 1024 * 1024

    @JvmStatic
    fun isSupported(context: Context, container: Container?): Boolean {
        if (!EXPERIMENTAL_AUTOMATION_READY) return false
        if (container == null) return false
        if (!container.wineVersion.contains("arm64ec", ignoreCase = true)) return false
        if (!container.emulator.equals("FEXCore", ignoreCase = true)) return false
        return runCatching {
            val manager = ContentsManager(context)
            manager.syncContents()
            val profile = manager.getProfileByEntryName("fexcore-${container.fexCoreVersion}") ?: return false
            profile.fileList?.any { it.source.endsWith("FEXOfflineCompiler64.exe", ignoreCase = true) } == true
        }.getOrDefault(false)
    }

    @JvmStatic
    fun status(context: Context, appId: String): FexCacheStatus {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull()
            ?: return FexCacheStatus(FexCacheState.NONE, supported = false, cacheBytes = 0)
        if (!isSupported(context, container)) {
            return FexCacheStatus(FexCacheState.NONE, supported = false, cacheBytes = 0)
        }
        val store = store(context, container)
        val record = store.readState()
        val now = System.currentTimeMillis()
        var state = record.state
        var reason = record.reason

        when (state) {
            FexCacheState.READY -> {
                val expected = identity(context, container)
                val metadata = record.metadata
                when {
                    metadata == null -> {
                        state = FexCacheState.NEEDS_REPREPARE
                        reason = "missing metadata"
                    }
                    !metadata.compatibleWith(expected) -> {
                        state = FexCacheState.NEEDS_REPREPARE
                        reason = "game, FEX build or preset changed"
                    }
                    !store.hasCacheArtifacts() -> {
                        state = FexCacheState.NEEDS_REPREPARE
                        reason = "cache files are missing"
                    }
                }
            }
            FexCacheState.PREPARING -> {
                if (record.runStartedAt == 0L && now - record.updatedAt > STATE_MAX_AGE_MS) {
                    state = FexCacheState.NEEDS_REPREPARE
                    reason = "preparation was not started"
                }
            }
            FexCacheState.COMPILING -> {
                if (now - record.updatedAt > COMPILE_MAX_AGE_MS) {
                    state = FexCacheState.NEEDS_REPREPARE
                    reason = "compilation was interrupted"
                }
            }
            else -> Unit
        }

        return FexCacheStatus(
            state = state,
            supported = true,
            cacheBytes = store.cacheBytes(),
            executablePath = container.executablePath,
            reason = reason,
            canPrepare = state != FexCacheState.PREPARING && state != FexCacheState.COMPILING,
            canClear = state != FexCacheState.NONE,
            canRecompile = state != FexCacheState.PREPARING && state != FexCacheState.COMPILING &&
                store.hasCodeMap(),
        )
    }

    /**
     * Experimental: rebuilds the translation cache from code maps already recorded by a
     * preparation session, without launching the game again. Returns a human-readable result so
     * the UI can show what happened.
     */
    fun recompile(context: Context, appId: String): String {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull()
            ?: return "Recompile failed: game container not found"
        if (!isSupported(context, container)) return "Recompile unavailable for this FEX build"
        val store = store(context, container)
        val hasMaps = store.hasCodeMap()
        if (!hasMaps) return "Nothing to recompile: run a preparation session first"
        return store.tryLock()?.use {
            store.beginPreparation()
            store.markRunStarted()
            store.markCompiling()
            val ok = finalizeIfNeeded(context, appId)
            if (ok) "Cache rebuilt: ${store.cacheBytes() / 1_000_000} MB" else {
                val reason = store.readState().reason.ifBlank { "compiler produced no cache files" }
                "Recompile failed: $reason"
            }
        } ?: "Another preparation is running. Try again when it finishes."
    }

    /** Marks a preparation session. Returns false when another preparation already owns the game. */
    @JvmStatic
    fun requestPreparation(context: Context, appId: String): Boolean {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull() ?: return false
        if (!isSupported(context, container)) return false
        val store = store(context, container)
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        if (free < MIN_FREE_BYTES) {
            store.markFailed("not enough free space")
            return false
        }
        return store.tryLock()?.use {
            store.beginPreparation()
            true
        } ?: false
    }

    /**
     * Returns FEX environment for this launch, or null for a normal launch. For a prepared cache
     * this also revalidates the metadata; a mismatch downgrades to a normal launch instead of
     * loading stale translated code.
     */
    @JvmStatic
    fun launchEnv(context: Context, container: Container): Map<String, String>? {
        if (!isSupported(context, container)) return null
        val store = store(context, container)
        val record = store.readState()
        val now = System.currentTimeMillis()
        return when (record.state) {
            FexCacheState.READY -> {
                val expected = identity(context, container)
                if (record.metadata == null || !record.metadata.compatibleWith(expected) || !store.hasCacheArtifacts()) {
                    val old = record.metadata
                    if (old != null) {
                        val diffs = listOf(
                            "fexVersion" to (old.fexVersion to expected.fexVersion),
                            "fexBuildHash" to (old.fexBuildHash to expected.fexBuildHash),
                            "configHash" to (old.configHash to expected.configHash),
                            "deviceFingerprint" to (old.deviceFingerprint to expected.deviceFingerprint),
                            "executableSize" to (old.executableSize to expected.executableSize),
                            "executableMtime" to (old.executableMtime to expected.executableMtime),
                            "executableSha256" to (old.executableSha256 to expected.executableSha256),
                        ).filter { it.second.first != it.second.second }
                            .map { "${it.first}: ${it.second.first.toString().take(12)} -> ${it.second.second.toString().take(12)}" }
                        android.util.Log.w("FexCodeCache", "READY invalidated for ${container.id}: ${diffs.ifEmpty { listOf("artifacts missing") }}")
                    }
                    store.markNeedsReprepare("cache no longer matches the game")
                    null
                } else {
                    store.writeState(record.copy(updatedAt = now))
                    env(store, "load")
                }
            }
            FexCacheState.PREPARING -> {
                if (record.runStartedAt != 0L) return null
                store.markRunStarted(now)
                env(store, "prepare")
            }
            else -> null
        }
    }

    /**
     * Runs the Windows offline compiler once a preparation session has exited. Safe to call from
     * screens and app start; the file lock makes it idempotent.
     */
    @JvmStatic
    fun finalizeIfNeeded(context: Context, appId: String): Boolean {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull() ?: return false
        if (!isSupported(context, container)) return false
        val store = store(context, container)
        val record = store.readState()
        if (record.state != FexCacheState.PREPARING && record.state != FexCacheState.COMPILING) return false
        if (record.state == FexCacheState.COMPILING) {
            store.markNeedsReprepare("compilation was interrupted")
            return false
        }
        if (record.runStartedAt == 0L) {
            store.markNeedsReprepare("preparation session never started")
            return false
        }
        if (!store.hasCodeMap()) {
            store.markFailed("no code map was produced; play further into the game")
            return false
        }

        return store.tryLock()?.use {
            val lockedRecord = store.readState()
            if (lockedRecord.state != FexCacheState.PREPARING) return@use false
            store.markCompiling()
            val compileStarted = System.currentTimeMillis()
            try {
                val output = FexCacheToolRunner.runCompiler(context, container, store.windowsCacheDir)
                Timber.i("FEX offline compiler: %s", output.takeLast(2_000))
                val compilerSucceeded = output.startsWith("compiler exit 0") ||
                    output.contains("Successfully populated cache") ||
                    output.contains("Cache up to date")
                if (!compilerSucceeded || !store.hasCacheArtifacts()) {
                    val exitLine = output.lineSequence().firstOrNull { it.startsWith("compiler exit") }
                    store.markFailed(exitLine ?: "compiler produced no cache files")
                    return@use false
                }
                val metadata = identity(context, container).copy(
                    preparedAt = System.currentTimeMillis(),
                    prepareDurationMs = compileStarted - lockedRecord.runStartedAt,
                    compileDurationMs = System.currentTimeMillis() - compileStarted,
                    cacheBytes = store.cacheBytes(),
                )
                store.markReady(metadata)
                true
            } catch (error: Throwable) {
                Timber.e(error, "FEX cache compilation failed")
                store.markFailed(error.message ?: error.javaClass.simpleName)
                false
            }
        } ?: false
    }

    @JvmStatic
    fun finalizeAllPending(context: Context) {
        runCatching {
            com.winlator.container.ContainerManager(context).containers.forEach { container ->
                val record = store(context, container).readState()
                if (record.state == FexCacheState.PREPARING && record.runStartedAt > 0) {
                    finalizeIfNeeded(context, container.id)
                }
            }
        }.onFailure { Timber.w(it, "Could not reconcile pending FEX cache preparations") }
    }

    @JvmStatic
    fun clear(context: Context, appId: String): Boolean {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull() ?: return false
        val store = store(context, container)
        return store.tryLock()?.use { store.clear() } ?: false
    }

    private fun env(store: FexCacheStore, mode: String): Map<String, String> {
        val stamp = System.currentTimeMillis()
        val logName = "fex-$mode-$stamp.log"
        return mapOf(
            "FEX_APP_CACHE_LOCATION" to store.windowsCacheDir,
            "FEX_ENABLECODECACHINGWIP" to "1",
            "FEX_SILENTLOG" to "0",
            "FEX_OUTPUTLOG" to store.windowsCacheDir + logName,
            // Wine routes FEX's log through __wine_dbg_output; enable the channel so the
            // "Loaded cache:" / "Cache load:" lines reach logcat and the session log.
            "WINEDEBUG" to "+fex",
        )
    }

    private fun store(context: Context, container: Container): FexCacheStore {
        val id = safeId(container.id)
        val prefix = File(container.rootDir, ".wine")
        val hostCache = File(prefix, "drive_c/ProgramData/Gameplay/FexCache/$id")
        return FexCacheStore(
            appId = container.id,
            rootDir = File(context.filesDir, "fex-cache/$id"),
            hostCacheDir = hostCache,
            windowsCacheDir = WINDOWS_CACHE_ROOT + id + "\\",
            clock = System::currentTimeMillis,
        )
    }

    private fun identity(context: Context, container: Container): FexCacheMetadata {
        val prefix = File(container.rootDir, ".wine")
        val executable = resolveExecutable(container)
        val env = FEXCorePresetManager.getEnvVars(context, container.fexCorePreset)
        val sorted = sortedMapOf<String, String>().apply { env.forEach { put(it, env.get(it)) } }
        return FexCacheMetadata(
            appId = container.id,
            fexVersion = container.fexCoreVersion,
            fexBuildHash = hashFiles(
                File(prefix, "drive_c/windows/system32/libwow64fex.dll"),
                File(prefix, "drive_c/windows/system32/libarm64ecfex.dll"),
            ),
            configHash = sha256(
                buildString {
                    append("wine=").append(container.wineVersion).append('\n')
                    append("emulator=").append(container.emulator).append('\n')
                    append("variant=").append(container.containerVariant).append('\n')
                    append("preset=").append(container.fexCorePreset).append('\n')
                    sorted.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
                },
            ),
            deviceFingerprint = sha256(
                listOf(
                    Build.MANUFACTURER,
                    Build.MODEL,
                    Build.DEVICE,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else "",
                    Build.FINGERPRINT,
                    Build.VERSION.RELEASE,
                    Build.SUPPORTED_ABIS.joinToString(","),
                ).joinToString("|"),
            ),
            executablePath = container.executablePath,
            executableSize = executable?.length() ?: 0,
            executableMtime = executable?.lastModified() ?: 0,
            executableSha256 = executable?.let(::sha256File).orEmpty(),
            preparedAt = 0,
            prepareDurationMs = 0,
            compileDurationMs = 0,
            cacheBytes = 0,
        )
    }

    private fun resolveExecutable(container: Container): File? {
        val exe = container.executablePath.trim().replace('\\', '/')
        if (exe.isEmpty()) return null
        // Drives are stored concatenated without a separator (e.g. "A:/a/bGOTYD:/c"), so each
        // root ends where the next "<letter>:/" marker begins.
        val roots = Regex("([A-Za-z]):(/.*?)(?=[A-Za-z]:/|$)").findAll(container.drives)
            .associate { it.groupValues[1].uppercase(Locale.ENGLISH) to it.groupValues[2].trim() }
        val drive = exe.substringBefore(':', "").uppercase(Locale.ENGLISH)
        val relative = if (drive.length == 1 && roots.containsKey(drive)) {
            exe.substringAfter(':', exe).trimStart('/')
        } else {
            exe.trimStart('/')
        }
        val root = roots[drive] ?: roots["A"] ?: return null
        return File(root, relative).takeIf { it.isFile }
    }

    private fun hashFiles(vararg files: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { file ->
            digest.update(file.name.toByteArray())
            digest.update(file.length().toString().toByteArray())
            if (file.isFile) FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun safeId(appId: String): String {
        val cleaned = appId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        return if (cleaned == appId) cleaned else cleaned + "-" + sha256(appId).take(8)
    }
}
