package org.buran.runtime

import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

internal class ExternalWindowsLaunch private constructor(
    val key: String,
    val game: InstalledGame,
    val directory: File,
    val workingDirectory: File,
    val environment: Map<String, String>,
    val steamAppId: Int?,
    val steamUserDirectory: File?,
    val steamRemoteDirectory: File?,
) {
    val relativeWorkingDirectory: String
        get() = workingDirectory.relativeTo(directory).invariantSeparatorsPath

    companion object {
        private val variableName = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val reservedPrefixes = listOf(
            "ANDROID_", "BURAN_", "LD_", "DYLD_", "PROOT_", "BOX64_", "BOX86_", "FEX_", "VK_", "PULSE_",
        )
        private val reservedNames = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "PREFIX", "TMPDIR", "TMP", "TEMP", "SHELL", "DISPLAY", "XAUTHORITY",
            "BOOTCLASSPATH", "CLASSPATH", "BASH_ENV", "ENV", "IFS", "PWD", "OLDPWD",
            "GCONV_PATH", "GLIBC_TUNABLES", "LOCPATH", "NLSPATH", "GIO_EXTRA_MODULES", "GTK_PATH",
            "GDK_PIXBUF_MODULE_FILE", "LIBGL_DRIVERS_PATH", "LIBGL_DRIVERS_DIR", "LIBGL_DRIVER_PATH",
            "MESA_LOADER_DRIVER_OVERRIDE", "HODLL", "WINESERVER", "WINEPREFIX", "WINEARCH",
            "WINELOADER", "WINELOADERNOEXEC", "WINEDLLPATH", "WINEDLLOVERRIDES", "WINEBOOTSTRAPMODE", "WINEESYNC", "WINEFSYNC",
            "PROTON_NO_NTSYNC",
        )

        fun resolve(
            key: String, directory: File, executable: String, bits: Int,
            workingDirectory: String? = null, environment: List<String> = emptyList(),
            steamAppId: Int? = null,
            steamUserDirectory: File? = null,
            steamRemoteDirectory: File? = null,
        ): ExternalWindowsLaunch {
            require(key.isNotBlank() && '\u0000' !in key) { "Укажите постоянный ключ установки" }
            require(bits == 32 || bits == 64) { "Нужен Windows EXE x86 или x86-64" }
            require(steamAppId == null || steamAppId > 0) { "Недопустимый идентификатор игры Steam" }
            require(directory.isAbsolute && '\u0000' !in directory.path)
            val root = directory.canonicalFile
            require(root.isDirectory && root.canRead() && root.canExecute()) { "Каталог установки недоступен: $root" }
            fun resolveInside(path: String): File {
                require(path.isNotEmpty() && '\u0000' !in path) { "Недопустимый путь установки" }
                val file = File(path).let { if (it.isAbsolute) it else root.resolve(it) }.canonicalFile
                require(file.toPath().startsWith(root.toPath())) { "Путь находится за пределами установки: $path" }
                return file
            }
            val entry = resolveInside(executable)
            require(entry.isFile && entry.canRead() && entry.extension.equals("exe", ignoreCase = true)) {
                "EXE установки недоступен: $executable"
            }
            fun windowsRelativePath(file: File): String {
                val relative = file.relativeTo(root)
                require(relative.toPath().none { part ->
                    val name = part.toString()
                    name.endsWith(' ') || name.endsWith('.') || name.any { it.isISOControl() || it in "\\:<>\"|?*" }
                }) { "Путь установки содержит недопустимое имя: $relative" }
                return relative.invariantSeparatorsPath
            }
            val cwd = workingDirectory?.let(::resolveInside) ?: checkNotNull(entry.parentFile)
            require(cwd.isDirectory && cwd.canRead() && cwd.canExecute()) { "Рабочий каталог недоступен: $cwd" }
            val relativeExe = windowsRelativePath(entry)
            windowsRelativePath(cwd)
            val variables = linkedMapOf<String, String>()
            val names = mutableSetOf<String>()
            for (assignment in environment) {
                val separator = assignment.indexOf('=')
                require(separator > 0 && '\u0000' !in assignment) { "Переменные среды требуют формат NAME=VALUE" }
                val name = assignment.substring(0, separator)
                val upper = name.uppercase(Locale.ROOT)
                require(variableName.matches(name) && upper !in reservedNames && reservedPrefixes.none(upper::startsWith)) {
                    "Переменная среды недоступна для запуска игры: $name"
                }
                require(names.add(upper)) { "Повторное имя переменной среды: $name" }
                variables[name] = assignment.substring(separator + 1)
            }
            val id = MessageDigest.getInstance("SHA-256")
                .digest("buran-external-installation\u0000$key".toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }
            val remote = steamRemoteDirectory?.canonicalFile?.also {
                require(steamAppId != null && it.isDirectory && it.toPath().none { part -> ':' in part.toString() } &&
                    it.name == "remote" && it.parentFile?.name == steamAppId.toString() &&
                    (it.parentFile?.parentFile?.name?.toLongOrNull() ?: 0L) > 0L
                ) { "Недопустимая папка сохранений Steam" }
            }
            return ExternalWindowsLaunch(
                key, InstalledGame(id, relativeExe, bits), root, cwd, Collections.unmodifiableMap(variables), steamAppId,
                steamUserDirectory?.canonicalFile?.takeIf { steamAppId != null && it.isDirectory },
                remote,
            )
        }
    }
}
