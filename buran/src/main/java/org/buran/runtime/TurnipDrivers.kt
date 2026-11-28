package org.buran.runtime

import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.DigestOutputStream
import java.security.MessageDigest

internal object TurnipDrivers {
    data class Driver(val directory: File, val libraryName: String, val title: String)
    data class Choice(val id: String, val title: String)

    private const val bundledId = "bundled"
    private const val bundledLibrary = "vulkan.ad07xx.so"
    private const val bundledTitle = "Встроенный Mesa Turnip 25.1.0 (Adreno 7xx)"
    private val importedId = Regex("zip-[0-9a-f]{64}")
    private var bundled: Driver? = null

    private fun root(context: Context) = context.buranFilesDir.resolve("turnip-drivers")

    fun fromGameplay(context: Context, graphics: Bundle?): Driver? {
        val value = graphics?.getString("turnipDirectory") ?: return null
        val parent = context.filesDir.resolve("contents/adrenotools").canonicalFile
        val directory = File(value).canonicalFile
        require(directory.parentFile == parent && directory.isDirectory) { "Пакет драйвера Gameplay недоступен" }
        val metadata = TurnipArchive.readMetadata(directory, Build.VERSION.SDK_INT)
        require(directory.resolve(metadata.libraryName).canonicalFile.parentFile == directory) {
            "Файл драйвера находится вне установленного пакета"
        }
        return Driver(directory, metadata.libraryName, metadata.title)
    }

    fun bionicEnvironment(context: Context, graphics: Bundle?, systemFallback: Boolean = false,
                          hasNativeDxvk: Boolean = false): Map<String, String> {
        val driver = fromGameplay(context, graphics)
        if (driver == null && !systemFallback) return emptyMap()
        val image = context.filesDir.resolve("imagefs/usr")
        val libraries = image.resolve("lib")
        val icds = image.resolve("share/vulkan/icd.d")
        val freedreno = icds.resolve("freedreno_icd.aarch64.json")
        val direct = hasNativeDxvk && graphics?.getBoolean("directFreedreno") == true &&
            graphics.getString("dxvkVersion").orEmpty().matches(Regex("[12]\\.[0-9][A-Za-z0-9.+_-]*")) &&
            freedreno.isFile && libraries.resolve("libvulkan_freedreno.so").isFile
        val icd = if (direct) freedreno else icds.resolve("wrapper_icd.aarch64.json")
        check(icd.isFile && (direct || libraries.resolve("libvulkan_wrapper.so").isFile)) {
            "В ImageFS отсутствует Vulkan wrapper для установленного драйвера Gameplay"
        }
        return buildMap {
            put("VK_DRIVER_FILES", icd.path)
            put("VK_ICD_FILENAMES", icd.path)
            val presentMode = graphics?.getString("presentMode")
            if (presentMode != null && presentMode in setOf("immediate", "mailbox", "fifo", "relaxed")) {
                put("MESA_VK_WSI_PRESENT_MODE", presentMode)
                if (!direct && presentMode == "immediate") put("WRAPPER_MAX_IMAGE_COUNT", "1")
            }
            if (graphics?.getBoolean("useDRI3", true) == false) put("MESA_VK_WSI_DEBUG", "sw")
            if (systemFallback) {
                put("GALLIUM_DRIVER", "zink")
                put("LIBGL_KOPPER_DISABLE", "true")
            }
            if (!direct && driver != null) {
                put("ADRENOTOOLS_DRIVER_PATH", driver.directory.path + "/")
                put("ADRENOTOOLS_DRIVER_NAME", driver.libraryName)
                put("ADRENOTOOLS_HOOKS_PATH", libraries.path)
                Regex("""\bVulkan (1\.\d+\.\d+)\b""", RegexOption.IGNORE_CASE).find(driver.title)?.let {
                    put("WRAPPER_VK_VERSION", it.groupValues[1])
                }
            }
        }
    }

    @Synchronized
    fun selected(context: Context): Driver {
        val id = selectedId(context)
        return if (id == bundledId) builtIn(context) else checkNotNull(installed(context, id)) {
            "Выбранный Turnip недоступен. Выберите другую версию."
        }
    }

    @Synchronized
    fun selectedId(context: Context): String {
        val selection = root(context).resolve("selected")
        return if (selection.isFile) selection.readText().trim() else bundledId
    }

    @Synchronized
    fun choices(context: Context): List<Choice> {
        val imported = root(context).listFiles().orEmpty().mapNotNull {
            runCatching { installed(context, it.name)?.let { driver -> Choice(it.name, driver.title) } }.getOrNull()
        }.sortedBy { it.title }
        return listOf(Choice(bundledId, bundledTitle)) + imported
    }

    @Synchronized
    fun select(context: Context, id: String) {
        require(id == bundledId || installed(context, id) != null) { "Версия Turnip недоступна" }
        withStoreLock(context) { parent ->
            val selection = parent.resolve("selected")
            val pending = Files.createTempFile(parent.toPath(), ".selected-", ".tmp").toFile()
            try {
                pending.writeText("$id\n")
                Files.move(pending.toPath(), selection.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
    }

    @Synchronized
    fun importZip(context: Context, input: InputStream): Choice = inStaging(context) { staging ->
        val archive = staging.resolve("driver.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(archive.outputStream().buffered(), digest).use { output ->
            TurnipArchive.copyBounded(input, output, TurnipArchive.archiveLimit)
        }
        val id = "zip-${digestId(digest)}"
        val destination = root(context).resolve(id)
        val existing = installed(context, id)
        if (existing != null) {
            Choice(id, existing.title)
        } else {
            val directory = staging.resolve("package").apply { check(mkdir()) }
            val metadata = TurnipArchive.unpack(archive, directory, Build.VERSION.SDK_INT)
            Files.move(directory.toPath(), destination.toPath(), ATOMIC_MOVE)
            Choice(id, metadata.title)
        }
    }

    private fun installed(context: Context, id: String): Driver? {
        if (!importedId.matches(id)) return null
        val directory = root(context).resolve(id)
        if (!directory.isDirectory) return null
        val metadata = TurnipArchive.readMetadata(directory, Build.VERSION.SDK_INT)
        return Driver(directory, metadata.libraryName, metadata.title)
    }

    private fun builtIn(context: Context): Driver {
        bundled?.let { if (it.directory.resolve(it.libraryName).isFile) return it }
        return inStaging(context) { staging ->
            val directory = staging.resolve("package").apply { check(mkdir()) }
            val library = directory.resolve(bundledLibrary)
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("gamescope-host/$bundledLibrary").use { input ->
                DigestOutputStream(library.outputStream().buffered(), digest).use { output ->
                    TurnipArchive.copyBounded(input, output, TurnipArchive.archiveLimit)
                }
            }
            TurnipArchive.validateLibrary(library)
            check(library.setReadOnly()) { "Не удалось защитить встроенный драйвер от изменений" }
            val destination = root(context).resolve("builtin-${digestId(digest)}")
            if (!destination.isDirectory)
                Files.move(directory.toPath(), destination.toPath(), ATOMIC_MOVE)
            TurnipArchive.validateLibrary(destination.resolve(bundledLibrary))
            Driver(destination, bundledLibrary, bundledTitle).also { bundled = it }
        }
    }

    private fun digestId(digest: MessageDigest): String = digest.digest().joinToString("") { "%02x".format(it) }

    private fun <T> inStaging(context: Context, action: (File) -> T): T = withStoreLock(context) { parent ->
        // Every installer holds this same cross-process lock until its staging directory is removed.
        parent.listFiles()?.filter { it.name.startsWith(".installing-") }?.forEach { it.deleteRecursively() }
        val staging = Files.createTempDirectory(parent.toPath(), ".installing-").toFile()
        try {
            action(staging)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun <T> withStoreLock(context: Context, action: (File) -> T): T {
        val parent = root(context)
        check(parent.isDirectory || parent.mkdirs() || parent.isDirectory) { "Не удалось создать каталог драйверов" }
        RandomAccessFile(parent.resolve(".store.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { return action(parent) }
        }
    }
}
