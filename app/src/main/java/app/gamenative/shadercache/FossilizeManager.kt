package app.gamenative.shadercache

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.events.AndroidEvent
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Local Fossilize capture and replay for Bionic Wine sessions. */
object FossilizeManager {
    const val EXTRA_ENABLED = "fossilizeEnabled"
    private const val LAYER_LIB = "libfossilize_layer.so"
    private const val REPLAY_BIN = "libfossilize_replay.so"
    private const val MAX_RECORD_BYTES = 200L * 1024 * 1024
    private const val REPLAY_LIMIT_MS = 60_000L

    /** Use the final guest environment so replay and recording select the same Vulkan driver and cache. */
    @JvmStatic
    fun prepare(
        context: Context,
        container: Container,
        env: EnvVars,
        cancelled: BooleanSupplier,
        replayLibraryExclusion: File?,
    ): Boolean = prepare(context, container, env, cancelled, false, replayLibraryExclusion) {
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.fossilize_preparing)))
    }

    internal fun prepareEmbedded(context: Context, container: Container, env: EnvVars,
                                 cancelled: BooleanSupplier, onReplay: Runnable): Boolean =
        prepare(context, container, env, cancelled, true, null, onReplay::run)

    private fun prepare(context: Context, container: Container, env: EnvVars,
                        cancelled: BooleanSupplier, embedded: Boolean, replayLibraryExclusion: File?,
                        onReplay: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !container.getExtra(EXTRA_ENABLED).toBoolean() || cancelled.asBoolean) return false
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val layer = File(nativeDir, LAYER_LIB)
        val replayer = File(nativeDir, REPLAY_BIN)
        if (!layer.isFile || !replayer.isFile) return false

        return try {
            val sharedRoot = File(container.rootDir, ".cache/fossilize")
            val root = if (embedded) File(sharedRoot, "buran") else sharedRoot
            val records = File(root, "record")
            val layerDir = (if (embedded) File(root, "implicit_layer.d")
                else File(container.rootDir, ".local/share/vulkan/implicit_layer.d")).apply { mkdirs() }
            records.mkdirs()
            val own = retainedArchives(records)
            val previous = own + if (embedded) {
                retainedArchives(File(sharedRoot, "record"), MAX_RECORD_BYTES - own.sumOf(File::length), prune = false)
            } else emptyList()
            val replayDir = File(root, "replayed").apply { mkdirs() }
            if (previous.isNotEmpty()) {
                try {
                    val profile = "${Build.FINGERPRINT}|${container.graphicsDriver}|${container.graphicsDriverConfig}"
                    val identity = if (embedded) "$profile|buran|${container.dxWrapper}|${container.dxWrapperConfig}" else profile
                    val replayEnv = if (replayLibraryExclusion == null) env else replayEnvironment(env, replayLibraryExclusion.absolutePath)
                    replay(identity, replayer, replayEnv, replayDir, previous, cancelled, embedded, onReplay)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                } catch (e: Exception) {
                    Timber.w(e, "Fossilize replay failed")
                }
            }
            if (cancelled.asBoolean) return false

            val manifest = JSONObject().put("file_format_version", "1.0.0").put(
                "layer", JSONObject()
                    .put("name", "VK_LAYER_fossilize")
                    .put("type", "GLOBAL")
                    .put("library_path", layer.absolutePath)
                    .put("api_version", "1.4.303")
                    .put("implementation_version", "1")
                    .put("description", "Fossilize capture layer")
                    .put("functions", JSONObject()
                        .put("vkGetInstanceProcAddr", "VK_LAYER_fossilize_GetInstanceProcAddr")
                        .put("vkGetDeviceProcAddr", "VK_LAYER_fossilize_GetDeviceProcAddr"))
                    .put("enable_environment", JSONObject().put("FOSSILIZE", "1"))
                    .put("disable_environment", JSONObject().put("DISABLE_FOSSILIZE", "1")),
            )
            File(layerDir, "VkLayer_fossilize.json").writeText(manifest.toString())

            val filter = File(root, "application_filter.json")
            filter.writeText(JSONObject()
                .put("asset", "FossilizeApplicationInfoFilter")
                .put("version", 1)
                .put("blacklistedApplicationNames", JSONArray(listOf("gamescope", "Xwayland")))
                .put("blacklistedEngineNames", JSONArray(listOf("mesa zink")))
                .toString())

            val session = Files.createTempDirectory(records.toPath(), "session-").toFile()
            env.remove("DISABLE_FOSSILIZE")
            env.put("FOSSILIZE_DUMP_PATH", File(session, "fossilize").absolutePath)
            env.put("FOSSILIZE_APPLICATION_INFO_FILTER_PATH", filter.absolutePath)
            env.remove("FOSSILIZE_DUMP_PATH_READ_ONLY")
            if (previous.isNotEmpty()) {
                env.put("FOSSILIZE_DUMP_PATH_READ_ONLY", previous.joinToString(":") { it.absolutePath })
            }
            env.put(
                "VK_ADD_IMPLICIT_LAYER_PATH",
                listOf(env.get("VK_ADD_IMPLICIT_LAYER_PATH"), layerDir.absolutePath)
                    .filter { it.isNotEmpty() }.joinToString(":"),
            )
            Timber.i("Fossilize recording at %s", session)
            true
        } catch (e: Exception) {
            Timber.w(e, "Fossilize preparation failed")
            false
        }
    }

    private fun retainedArchives(records: File, budget: Long = MAX_RECORD_BYTES, prune: Boolean = true): List<File> {
        var retainedBytes = 0L
        return records.listFiles().orEmpty().filter { Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
            .sortedByDescending { it.lastModified() }.flatMap { session ->
            session.listFiles().orEmpty().filter { Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) && it.extension == "foz" }.filter { db ->
                val size = db.length()
                if (size <= FozArchive.HEADER_BYTES || retainedBytes + size > budget) {
                    if (prune) db.delete()
                    false
                } else {
                    retainedBytes += size
                    true
                }
            }.also {
                if (prune && session.list().isNullOrEmpty()) session.delete()
            }
        }
    }

    private fun replay(
        profile: String,
        executable: File,
        env: EnvVars,
        workDir: File,
        databases: List<File>,
        cancelled: BooleanSupplier,
        embedded: Boolean,
        onReplay: () -> Unit,
    ) {
        if (cancelled.asBoolean) return
        val driver = driverIdentity(profile, env, embedded)
        ReplayedPipelines.fold(workDir, driver)
        val known = ReplayedPipelines.known(workDir, driver)
        val pending = ReplayedPipelines.pending(databases, known)
        if (pending.isEmpty() || cancelled.asBoolean) return
        val cachePath = ReplayedPipelines.cachePath(workDir, driver)
        val databasePaths = databases.map(File::getAbsolutePath)
        // Fossilize filters reads in a single stream. Its multi-archive reader only filters enumeration.
        val replayPaths = if (embedded && databasePaths.size == 1) databasePaths + databasePaths else databasePaths
        val args = replayPaths + listOf(
            "--num-threads", (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4).toString(),
            "--timeout-seconds", "25",
            "--disable-rate-limiter",
            "--replayer-cache", cachePath.absolutePath,
        )
        if (!embedded) {
            if (runReplay(executable, env, workDir, args, cancelled, false, onReplay)) {
                ReplayedPipelines.fold(workDir, driver)
            }
            return
        }

        val stall = File(workDir, "${cachePath.name}.stalled")
        // Retry archives previously stalled by the replayer's graphics-library linking bug.
        // Successful pipeline stamps remain valid across this change.
        val identity = "gpl-v1:" + ReplayedPipelines.pendingIdentity(pending)
        if (ReplayedPipelines.isStalled(stall, identity)) return
        val previouslyRecorded = ReplayedPipelines.recorded(workDir, driver)
        val whitelist = File.createTempFile("pending-", ".foz", workDir)
        try {
            check(ReplayedPipelines.writeWhitelist(whitelist, pending)) { "Could not write Fossilize replay whitelist" }
            val finished = runReplay(executable, env, workDir, args + listOf(
                "--progress", "--on-disk-replay-whitelist", whitelist.absolutePath,
                "--on-disk-replay-whitelist-mask", "pipeline",
            ), cancelled, true, onReplay)
            if (finished) {
                ReplayedPipelines.fold(workDir, driver)
                if (!cancelled.asBoolean) {
                    val progressed = ReplayedPipelines.recorded(workDir, driver).any { it !in previouslyRecorded }
                    ReplayedPipelines.recordAttempt(stall, identity, progressed)
                }
            }
        } finally {
            whitelist.delete()
        }
    }

    private fun runReplay(
        executable: File,
        env: EnvVars,
        workDir: File,
        args: List<String>,
        cancelled: BooleanSupplier,
        embedded: Boolean,
        onReplay: () -> Unit,
    ): Boolean {
        val output = File(workDir, "last-replay.log")
        val command = if (executable.canExecute()) listOf(executable.absolutePath)
            else listOf("/system/bin/linker64", executable.absolutePath)
        val builder = ProcessBuilder(command + args)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(output)
        builder.environment().apply {
            clear()
            for (name in env) put(name, env.get(name))
            remove("FOSSILIZE")
            remove("FOSSILIZE_DUMP_PATH")
            remove("FOSSILIZE_DUMP_PATH_READ_ONLY")
            remove("REDIRECT_EXEC__PROC_SELF_EXE")
            if (embedded) remove("VK_ADD_IMPLICIT_LAYER_PATH")
            put("DISABLE_FOSSILIZE", "1")
        }
        if (cancelled.asBoolean) return false
        onReplay()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            Timber.w(e, "Fossilize replay could not start")
            return false
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + REPLAY_LIMIT_MS
            while (!cancelled.asBoolean && SystemClock.elapsedRealtime() < deadline) {
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    val status = process.exitValue()
                    Timber.i("Fossilize replay finished with exit %d; %s", status, output)
                    break
                }
            }
            if (process.isAlive && !cancelled.asBoolean) Timber.w("Fossilize replay timed out; %s", output)
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (runCatching { !process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(true)) {
                    process.destroyForcibly()
                    runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                }
            }
        }
        return !process.isAlive
    }

    internal fun replayEnvironment(env: EnvVars, excludedLibraryDir: String): EnvVars = EnvVars().apply {
        putAll(env)
        val paths = env.get("LD_LIBRARY_PATH").split(':').filterNot { it == excludedLibraryDir }
        put("LD_LIBRARY_PATH", paths.joinToString(":"))
    }

    internal fun driverIdentity(profile: String, env: EnvVars, embedded: Boolean = false): String = buildString {
        append(profile)
        val baseKeys = listOf(
            "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "LD_LIBRARY_PATH", "HOME", "XDG_CACHE_HOME",
            "MESA_SHADER_CACHE_DIR", "MESA_SHADER_CACHE_DISABLE", "MESA_DISK_CACHE_SINGLE_FILE",
            "ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_DRIVER_NAME", "TU_DEBUG",
        )
        val keys = if (embedded) baseKeys.filterNot { it == "LD_LIBRARY_PATH" || it == "HOME" } +
            listOf("WRAPPER_VK_VERSION", "GALLIUM_DRIVER", "LIBGL_KOPPER_DISABLE") else baseKeys
        for (key in keys) append('|').append(env.get(key))
        if (env.has("GN_VKSHIM_SYSTEM_DRIVER")) {
            append("|GN_VKSHIM_SYSTEM_DRIVER=").append(env.get("GN_VKSHIM_SYSTEM_DRIVER"))
        }

        val searchPaths = env.get("LD_LIBRARY_PATH").split(':').filter { it.isNotEmpty() }
        val driverFiles = LinkedHashSet<File>()
        val driverDir = env.get("ADRENOTOOLS_DRIVER_PATH")
        if (driverDir.isNotEmpty()) {
            File(driverDir).listFiles()?.filter { it.isFile && it.name.endsWith(".so") }
                ?.sortedBy { it.name }?.let(driverFiles::addAll)
        }
        val icds = env.get("VK_DRIVER_FILES").ifEmpty { env.get("VK_ICD_FILENAMES") }
        for (path in icds.split(':').filter { it.isNotEmpty() }) {
            val manifest = File(path)
            if (!manifest.isFile) continue
            val json = manifest.readText()
            append('|').append(json)
            val libraryPath = JSONObject(json).optJSONObject("ICD")?.optString("library_path").orEmpty()
            if (libraryPath.isEmpty()) continue
            val library = File(libraryPath)
            if (library.isAbsolute) {
                driverFiles.add(library)
            } else {
                (listOf(File(manifest.parentFile, libraryPath)) + searchPaths.map { File(it, libraryPath) })
                    .firstOrNull { it.isFile }?.let(driverFiles::add)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        for (file in driverFiles.filter { it.isFile }) {
            file.inputStream().buffered().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            append('|').append(digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
