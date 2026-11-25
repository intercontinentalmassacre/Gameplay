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
    ): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !container.getExtra(EXTRA_ENABLED).toBoolean() || cancelled.asBoolean) return false
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val layer = File(nativeDir, LAYER_LIB)
        val replayer = File(nativeDir, REPLAY_BIN)
        if (!layer.isFile || !replayer.isFile) return false

        return try {
            val root = File(container.rootDir, ".cache/fossilize")
            val records = File(root, "record")
            val layerDir = File(container.rootDir, ".local/share/vulkan/implicit_layer.d").apply { mkdirs() }
            records.mkdirs()
            val previous = retainedArchives(records)
            val replayDir = File(root, "replayed").apply { mkdirs() }
            if (previous.isNotEmpty()) {
                try {
                    replay(context, container, replayer, env, replayDir, previous, cancelled)
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

    private fun retainedArchives(records: File): List<File> {
        var retainedBytes = 0L
        return records.listFiles().orEmpty().sortedByDescending { it.lastModified() }.flatMap { session ->
            session.listFiles().orEmpty().filter { it.isFile && it.extension == "foz" }.filter { db ->
                val size = db.length()
                if (size <= FozArchive.HEADER_BYTES || retainedBytes + size > MAX_RECORD_BYTES) {
                    db.delete()
                    false
                } else {
                    retainedBytes += size
                    true
                }
            }.also {
                if (session.list().isNullOrEmpty()) session.delete()
            }
        }
    }

    private fun replay(
        context: Context,
        container: Container,
        executable: File,
        env: EnvVars,
        workDir: File,
        databases: List<File>,
        cancelled: BooleanSupplier,
    ) {
        val driver = driverIdentity("${Build.FINGERPRINT}|${container.graphicsDriver}|${container.graphicsDriverConfig}", env)
        ReplayedPipelines.fold(workDir, driver)
        val known = ReplayedPipelines.known(workDir, driver)
        if (databases.none { db -> ReplayedPipelines.pipelineNames(db).any { it !in known } }) return
        if (cancelled.asBoolean) return

        val args = databases.map { it.absolutePath } + listOf(
            "--num-threads", (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4).toString(),
            "--timeout-seconds", "25",
            "--disable-rate-limiter",
            "--replayer-cache", ReplayedPipelines.cachePath(workDir, driver).absolutePath,
        )
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
            put("DISABLE_FOSSILIZE", "1")
        }
        PluviaApp.events.emit(AndroidEvent.SetBootingSplashText(context.getString(R.string.fossilize_preparing)))
        val process = try {
            builder.start()
        } catch (e: IOException) {
            Timber.w(e, "Fossilize replay could not start")
            return
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
        } finally {
            if (process.isAlive) {
                process.destroy()
                if (runCatching { !process.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(true)) {
                    process.destroyForcibly()
                }
            }
            ReplayedPipelines.fold(workDir, driver)
        }
    }

    internal fun driverIdentity(profile: String, env: EnvVars): String = buildString {
        append(profile)
        for (key in listOf(
            "VK_DRIVER_FILES", "VK_ICD_FILENAMES", "LD_LIBRARY_PATH", "HOME", "XDG_CACHE_HOME",
            "MESA_SHADER_CACHE_DIR", "MESA_SHADER_CACHE_DISABLE", "MESA_DISK_CACHE_SINGLE_FILE",
            "ADRENOTOOLS_DRIVER_PATH", "ADRENOTOOLS_DRIVER_NAME", "TU_DEBUG",
        )) append('|').append(env.get(key))
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
