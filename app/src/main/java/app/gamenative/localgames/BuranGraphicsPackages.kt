package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import app.gamenative.shadercache.FossilizeManager
import app.gamenative.utils.ManifestComponentHelper
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestRepository
import app.gamenative.utils.downloader.DXWrapperDownloader
import com.winlator.container.Container
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.DXVKHelper
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import com.winlator.core.TarCompressorUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Locale
import org.json.JSONObject

/** Shares selected installed packages with the embedded runtime without modifying them. */
internal object BuranGraphicsPackages {
    const val EXTRA = "buranGraphics"

    fun attach(context: Context, container: Container?, intent: Intent) {
        if (container == null) return
        val graphics = Bundle()
        if (container.getExtra(FossilizeManager.EXTRA_ENABLED).toBoolean()) {
            graphics.putBoolean(FossilizeManager.EXTRA_ENABLED, true)
        }
        val wrapper = container.dxWrapper.orEmpty()
        val driverConfig = KeyValueSet(container.graphicsDriverConfig.orEmpty())
        val driver = driverConfig.get("version").ifBlank { container.graphicsDriverVersion.orEmpty() }
        if (wrapper.equals("dxvk", ignoreCase = true) || wrapper.startsWith("dxvk-", ignoreCase = true)) {
            val dxvkConfig = DXVKHelper.parseConfig(container.dxWrapperConfig)
            val version = dxvkConfig.get("version").orEmpty()
            val contents = ContentsManager(context).apply { syncContents() }
            val selectedVersion = wrapper.substringAfter('-', version).trim()
            val selected = contents.getProfileByEntryName("DXVK-$selectedVersion")
                ?: contents.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_DXVK).orEmpty().firstOrNull {
                    it.verName.equals(selectedVersion, ignoreCase = true)
                }
            if (selected?.type == ContentProfile.ContentType.CONTENT_TYPE_DXVK) {
                val install = ContentsManager.getInstallDir(context, selected).canonicalFile
                val root = ContentsManager.getContentTypeDir(context, selected.type).canonicalFile
                if (install.parentFile == root) {
                    val files = selected.fileList.orEmpty().mapNotNull { file ->
                        val target = file.target.orEmpty()
                        val folder = when (target.substringBefore('/')) {
                            "\${system32}" -> "system32"
                            "\${syswow64}" -> "syswow64"
                            else -> return@mapNotNull null
                        }
                        val name = target.substringAfter('/')
                        if (name !in dxvkLibraries) return@mapNotNull null
                        val source = install.resolve(file.source.orEmpty()).canonicalFile
                        if (!source.isFile || !source.toPath().startsWith(install.toPath())) return@mapNotNull null
                        "$folder/$name=${source.path}"
                    }
                    if (files.isNotEmpty()) graphics.putStringArrayList("dxvkFiles", ArrayList(files))
                }
            }
            if (!graphics.containsKey("dxvkFiles")) {
                val files = bundledOrCachedDxvk(context, selectedVersion)
                if (files.isNotEmpty()) graphics.putStringArrayList("dxvkFiles", ArrayList(files))
            }
            if (graphics.containsKey("dxvkFiles")) {
                graphics.putString("dxvkVersion", selectedVersion)
                val env = dxvkEnvironment(dxvkConfig, container.envVars)
                graphics.putBundle("dxvkEnvironment", Bundle().apply {
                    env.forEach { name -> putString(name, env.get(name)) }
                })
                if (driver.contains("turnip", ignoreCase = true) && driverConfig.get("adrenotoolsTurnip", "1") == "0") {
                    graphics.putBoolean("directFreedreno", true)
                }
            }
        }

        if (intent.getIntExtra("steamAppId", 0) > 0) {
            val mode = driverConfig.get("presentMode").trim().lowercase(Locale.ROOT)
            if (mode in setOf("immediate", "mailbox", "fifo", "relaxed")) graphics.putString("presentMode", mode)
            graphics.putBoolean("useDRI3", container.isUseDRI3)
        }
        val driverRoot = File(context.filesDir, "contents/adrenotools").canonicalFile
        if (driver.isNotBlank() && !driver.equals(AdrenotoolsManager.SYSTEM_DRIVER_ID, ignoreCase = true) &&
            driverRoot.isDirectory
        ) {
            val manager = AdrenotoolsManager(context)
            fun installed(id: String): File? {
                if (id in setOf(".", "..") || id.none { it.isLetterOrDigit() } ||
                    id.any { it in "/\\:" || it.isISOControl() }
                ) return null
                val file = driverRoot.resolve(id).canonicalFile
                return file.takeIf {
                    it.parentFile == driverRoot && it.isDirectory && manager.getLibraryName(id).isNotBlank()
                }
            }
            val directory = installed(driver) ?: run {
                val entries = ManifestRepository.loadCachedOrBundledManifest(context)
                    .items[ManifestContentTypes.DRIVER].orEmpty()
                ManifestComponentHelper.findManifestEntryForVersion(driver, entries)?.id?.let(::installed)
            }
            directory?.let { graphics.putString("turnipDirectory", it.path) }
        }
        if (!graphics.isEmpty) intent.putExtra(EXTRA, graphics)
    }

    internal fun dxvkEnvironment(config: KeyValueSet, containerEnv: String?): EnvVars {
        val result = EnvVars()
        DXVKHelper.setConfiguredEnvVars(config, result)
        val overrides = EnvVars(containerEnv)
        for (name in listOf("DXVK_ASYNC", "DXVK_GPLASYNCCACHE", "DXVK_STATE_CACHE",
            "DXVK_CONFIG", "DXVK_FEATURE_LEVEL", "DXVK_FRAME_RATE")) {
            if (overrides.has(name)) result.put(name, overrides.get(name))
        }
        return result
    }

    @Synchronized
    private fun bundledOrCachedDxvk(context: Context, version: String): List<String> {
        if (!version.matches(Regex("(?:async-)?[0-9][A-Za-z0-9._+-]{0,79}"))) return emptyList()
        val name = "dxvk-$version.tzst"
        val bundled = context.assets.list("dxwrapper").orEmpty().contains(name)
        val cache = context.filesDir.resolve(DXWrapperDownloader.DXWRAPPER_CACHE_DIR).canonicalFile
        val archive = cache.resolve(name).canonicalFile
        if (!bundled && (archive.parentFile != cache || !archive.isFile || archive.length() == 0L)) return emptyList()
        val revision = if (bundled) "apk-${File(context.applicationInfo.sourceDir).lastModified()}"
            else "cache-${archive.lastModified()}-${archive.length()}"
        val root = context.filesDir.resolve("buran/dxvk-selected").apply { mkdirs() }.canonicalFile
        val target = root.resolve("$version-$revision")
        val paths = listOf("system32", "syswow64").flatMap { folder -> dxvkLibraries.map { "$folder/$it" } }
        fun mappings(directory: File) = paths.mapNotNull { path ->
            val file = directory.resolve(path)
            if (Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) "$path=${file.path}" else null
        }
        if (target.isDirectory) return mappings(target)
        val staging = Files.createTempDirectory(root.toPath(), ".dxvk-").toFile()
        try {
            staging.resolve("system32").mkdir()
            staging.resolve("syswow64").mkdir()
            val input = if (bundled) context.assets.open("dxwrapper/$name") else archive.inputStream().buffered()
            val ok = input.use {
                TarCompressorUtils.extractWithoutLinks(TarCompressorUtils.Type.ZSTD, it, staging) { file, size ->
                    val relative = file.toPath().normalize().takeIf { path -> path.startsWith(staging.toPath()) }
                        ?.let { path -> staging.toPath().relativize(path).toString() }
                    relative?.takeIf { path -> path in paths && size in 1..32L * 1024 * 1024 }?.let(staging::resolve)
                }
            }
            if (!ok || mappings(staging).isEmpty()) return emptyList()
            Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
            return mappings(target)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** A local default for Android Proton when the container has no usable selected driver. */
    fun attachInstalledProtonFallback(context: Context, intent: Intent) {
        val graphics = intent.getBundleExtra(EXTRA) ?: Bundle()
        if (graphics.containsKey("turnipDirectory")) return
        val renderer = GPUInformation.getRenderer(context).orEmpty().lowercase(Locale.ROOT)
        val gpu = Regex("""adreno[^0-9]*([67][0-9]{2})\b""").find(renderer)?.groupValues?.get(1) ?: return
        val root = context.filesDir.resolve("contents/adrenotools").canonicalFile
        val manager = AdrenotoolsManager(context)
        val familyHint = if (gpu == "740") "sd8gen2" else null
        val candidates = manager.enumarateInstalledDrivers().mapNotNull { id ->
            val directory = root.resolve(id).canonicalFile
            val library = manager.getLibraryName(id)
            val metadata = directory.resolve("meta.json")
            if (directory.parentFile != root || !directory.isDirectory || library.isBlank() ||
                directory.resolve(library).canonicalFile.parentFile != directory || !directory.resolve(library).isFile ||
                !metadata.isFile || metadata.length() !in 1..65536
            ) return@mapNotNull null
            val data = runCatching { JSONObject(metadata.readText()) }.getOrNull() ?: return@mapNotNull null
            val minApi = data.optInt("minApi", -1)
            if (minApi !in 1..Build.VERSION.SDK_INT) return@mapNotNull null
            val label = "$id ${data.optString("name")} ${data.optString("description")}".lowercase(Locale.ROOT)
            if (!label.contains("turnip") && library != "vulkan.freedreno.so") return@mapNotNull null
            val compact = label.filter(Char::isLetterOrDigit)
            val namedGpu = Regex("""\b(?:adreno\s*|a)([678][0-9]{2})\b""").find(label)?.groupValues?.get(1)
            val rank = when {
                namedGpu != null && namedGpu != gpu -> return@mapNotNull null
                "sd8gen2" in compact && gpu != "740" -> return@mapNotNull null
                "sd8gen3" in compact && gpu != "750" -> return@mapNotNull null
                "gen8" in compact || "a8xx" in compact || "8elite" in compact -> return@mapNotNull null
                namedGpu == gpu || (familyHint != null && familyHint in compact) -> 0
                else -> 1
            }
            rank to directory
        }
        val chosen = candidates.sortedWith(compareBy<Pair<Int, File>> { it.first }.thenBy { it.second.name }).firstOrNull()
        if (chosen != null) {
            graphics.putString("turnipDirectory", chosen.second.path)
            intent.putExtra(EXTRA, graphics)
        }
    }

    private val dxvkLibraries = setOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
}
