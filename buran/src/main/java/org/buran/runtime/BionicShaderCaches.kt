package org.buran.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

internal object BionicShaderCaches {
    private val gameId = Regex("[a-f0-9]{32}")
    // DXVK 2.7 removed the legacy state cache.
    private val legacyDxvk = Regex("(?:async-)?(?:1\\.[0-9]+|2\\.[0-6])(?:[._+-][A-Za-z0-9._+-]+)?")

    fun dxvkStateCache(files: File, id: String, version: String, bits: Int): Map<String, String> {
        if (!gameId.matches(id) || version.length > 86 || !legacyDxvk.matches(version)) return emptyMap()
        return try {
            val root = files.resolve("shader-cache").canonicalFile
            val expected = root.resolve("$id/dxvk/$version/$bits")
            val directory = expected.canonicalFile
            require(directory == expected)
            Files.createDirectories(directory.toPath())
            // DXVK is a Windows DLL and opens the cache through Wine's Win32 file APIs.
            mapOf("DXVK_STATE_CACHE_PATH" to ("Z:" + directory.path.replace('/', '\\')))
        } catch (e: Exception) {
            Log.w("BuranShaderCache", "Could not prepare the persistent DXVK state cache", e)
            emptyMap()
        }
    }

    fun environment(context: Context, game: InstalledGame, image: File, graphics: Map<String, String>): Map<String, String> {
        if (!gameId.matches(game.id)) return emptyMap()
        return try {
            val driver = driverIdentity(image, graphics)
            val parent = context.buranFilesDir.resolve("shader-cache").canonicalFile
            val root = parent.resolve("${game.id}/$driver").canonicalFile
            require(root.parentFile?.parentFile == parent)
            val mesa = root.resolve("mesa")
            val wrapper = root.resolve("wrapper")
            listOf(mesa, wrapper).forEach { Files.createDirectories(it.toPath()) }
            buildMap {
                put("MESA_SHADER_CACHE_DIR", mesa.path)
                put("MESA_SHADER_CACHE_MAX_SIZE", "512M")
                if ("ADRENOTOOLS_HOOKS_PATH" in graphics) put("WRAPPER_CACHE_PATH", wrapper.path)
            }
        } catch (e: Exception) {
            Log.w("BuranShaderCache", "Could not prepare the persistent driver cache", e)
            emptyMap()
        }
    }

    private fun driverIdentity(image: File, graphics: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun label(value: String) {
            digest.update(value.toByteArray())
            digest.update(0)
        }
        val buffer = ByteArray(64 * 1024)
        fun hash(file: File) {
            label(file.name)
            label(file.length().toString())
            file.inputStream().buffered().use { input ->
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
        }
        label(Build.FINGERPRINT)
        for (key in listOf("WRAPPER_VK_VERSION", "GALLIUM_DRIVER", "TU_DEBUG")) {
            label(graphics[key].orEmpty())
        }
        val manifest = File(graphics.getValue("VK_DRIVER_FILES"))
        hash(manifest)
        val library = JSONObject(manifest.readText()).getJSONObject("ICD").getString("library_path")
        val relative = File(library)
        hash(if (relative.isAbsolute) relative else image.resolve("usr/lib").resolve(library))
        val customDriver = graphics["ADRENOTOOLS_DRIVER_PATH"]
        if (customDriver != null) {
            hash(File(customDriver, graphics.getValue("ADRENOTOOLS_DRIVER_NAME")))
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }
}
