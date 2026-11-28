package org.buran.runtime

import android.os.Bundle
import java.io.File

internal class SteamInstallDependencies private constructor(
    private val steps: List<Step>,
    private val registry: List<Registry>,
) {
    private data class Registry(val key: String, val name: String, val type: String, val value: String) {
        fun arguments(gameDirectory: String, is64Bit: Boolean): List<String> = listOf("reg", "add", key) +
            (if (name == "(Default)") listOf("/ve") else listOf("/v", name)) +
            listOf("/t", type, "/d", installPath(value, gameDirectory), "/f") +
            listOfNotNull(registryViews(key, is64Bit).first())
    }

    data class Step(
        val file: File,
        val args: List<String>,
        val key: String,
        val valueName: String,
        val minimum: Int,
    ) {
        fun wineArguments(windowsPath: String, gameDirectory: String): List<String> {
            val arguments = args.map { arg ->
                val property = arg.indexOf('=')
                if (property < 0) installPath(arg, gameDirectory)
                else arg.take(property + 1) + installPath(arg.drop(property + 1), gameDirectory)
            }
            return when (file.extension.lowercase()) {
                "msi" -> listOf("msiexec", "/i", windowsPath) + arguments
                "bat" -> listOf("cmd", "/d", "/c", windowsPath, "quiet")
                else -> listOf(windowsPath) + arguments
            }
        }
    }

    val isEmpty: Boolean get() = steps.isEmpty() && registry.isEmpty()

    fun runBionic(
        game: File, output: File, onInstalling: () -> Unit,
        verifiedInstalled: (File) -> Boolean = { false },
        command: (File, List<String>, File, Long) -> Int,
    ) {
        val gameDirectory = "Z:" + game.path.replace('/', '\\')
        for (entry in registry) {
            val status = command(game, entry.arguments(gameDirectory, is64Bit = true), output, 30)
            check(status == 0) { "Не удалось применить настройки реестра Steam: ${entry.key}" }
        }
        for (step in steps) {
            val directory = checkNotNull(step.file.parentFile)
            val views = registryViews(step.key, is64Bit = true)
            fun registry(view: String?, vararg args: String) =
                command(directory, listOf("reg") + args + listOfNotNull(view), output, 30)
            fun markerPresent() = views.any { view ->
                registry(view, "query", step.key, "/v", step.valueName) == 0 &&
                    (DWORD.find(output.readText())?.groupValues?.get(1)?.toLongOrNull(16) ?: 0) >= step.minimum
            }
            if (markerPresent() || verifiedInstalled(step.file)) {
                android.util.Log.d("BuranSteamDeps", "Already installed for this prefix: ${step.file.name}")
                continue
            }
            android.util.Log.i("BuranSteamDeps", "Installing local dependency: ${step.file.name}")
            onInstalling()
            val status = command(directory, step.wineArguments("Z:" + step.file.path.replace('/', '\\'), gameDirectory), output, 180)
            if (status == 0) {
                if (!markerPresent()) {
                    check(registry(views.first(), "add", step.key, "/v", step.valueName, "/t", "REG_DWORD", "/d",
                        step.minimum.toString(), "/f") == 0) { "Не удалось сохранить результат установки ${step.file.name}" }
                }
                android.util.Log.i("BuranSteamDeps", "Installed for this prefix: ${step.file.name}")
            } else {
                android.util.Log.w("BuranSteamDeps", "${step.file.name} exited with $status; will retry next launch")
                if (status < 0) error("Истекло время установки ${step.file.name}")
            }
        }
    }

    fun writeBoxPlan(output: File, gameDirectory: String, is64Bit: Boolean, windowsPath: (File) -> String) {
        output.bufferedWriter().use { stream ->
            for (entry in registry) {
                val args = entry.arguments(gameDirectory, is64Bit)
                (listOf("registry", args.size.toString()) + args).forEach { stream.appendLine(it) }
            }
            for (step in steps) {
                val path = windowsPath(step.file)
                val directory = path.removePrefix("Z:").substringBeforeLast('\\').replace('\\', '/')
                val args = step.wineArguments(path, gameDirectory)
                val views = registryViews(step.key, is64Bit).joinToString(" ") { it ?: "default" }
                (listOf("installer", step.key, step.valueName, step.minimum.toString(), views, directory, args.size.toString()) + args).forEach {
                    stream.appendLine(it)
                }
            }
        }
    }

    fun usesDirectory(directory: File): Boolean =
        steps.any { it.file.toPath().startsWith(directory.toPath()) }

    companion object {
        const val EXTRA = "buranSteamDependencies"
        private val DWORD = Regex("""REG_DWORD\s+0x([0-9a-f]+)""", RegexOption.IGNORE_CASE)

        internal fun registryViews(key: String, is64Bit: Boolean): List<String?> = when {
            !is64Bit -> listOf(null)
            key.split('\\').any { it.equals("Wow6432Node", ignoreCase = true) } -> listOf("/reg:64")
            else -> listOf("/reg:32", "/reg:64")
        }

        private fun installPath(value: String, gameDirectory: String): String =
            if (value.startsWith("A:", ignoreCase = true) &&
                (value.length == 2 || value[2] == '/' || value[2] == '\\')
            ) gameDirectory + value.drop(2).replace('/', '\\') else value

        @Suppress("DEPRECATION")
        fun resolve(bundle: Bundle?, request: ExternalWindowsLaunch): SteamInstallDependencies? {
            if (bundle == null || request.steamAppId == null) return null
            val game = request.directory.canonicalFile
            val shared = game.parentFile?.resolve("Steamworks Shared")?.canonicalFile?.takeIf {
                it.parentFile == game.parentFile && it.isDirectory
            }
            val steps = bundle.getParcelableArrayList<Bundle>("steps").orEmpty().take(64).mapNotNull {
                val file = File(it.getString("executable") ?: return@mapNotNull null).canonicalFile
                if (!file.isFile || ':' in file.path ||
                    (!file.toPath().startsWith(game.toPath()) && shared?.let { dir ->
                        file.toPath().startsWith(dir.toPath())
                    } != true)
                ) return@mapNotNull null
                val args = it.getStringArrayList("arguments").orEmpty()
                val key = it.getString("marker").orEmpty()
                val keyPath = key.substringBeforeLast('\\')
                val name = key.substringAfterLast('\\')
                if (keyPath == key || !keyPath.startsWith("HKLM\\") && !keyPath.startsWith("HKCU\\") ||
                    name.isBlank() || (args + listOf(key, file.path)).any { value -> value.any(Char::isISOControl) } ||
                    file.extension.lowercase() !in setOf("exe", "msi", "bat")
                ) return@mapNotNull null
                Step(file, args, keyPath, name, it.getInt("markerValue").coerceAtLeast(1))
            }
            val registry = bundle.getParcelableArrayList<Bundle>("registry").orEmpty().take(128).mapNotNull {
                val key = it.getString("key").orEmpty()
                val name = it.getString("name").orEmpty()
                val type = it.getString("type").orEmpty()
                val value = it.getString("value").orEmpty()
                if ((!key.startsWith("HKLM\\") && !key.startsWith("HKCU\\")) ||
                    type !in setOf("REG_SZ", "REG_DWORD") || listOf(key, name, value).any { it.any(Char::isISOControl) }
                ) return@mapNotNull null
                Registry(key, name, type, value)
            }
            return SteamInstallDependencies(steps, registry).takeUnless { it.isEmpty }
        }
    }
}
