package org.buran.runtime

import android.content.Context
import android.system.Os
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

internal object WindowsProbe {
    private const val prefixName = "wp-max11"

    fun runtimeAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libburan_winepaths.so").isFile &&
            context.assets.list("").orEmpty().contains("wine-runtime.zip")

    @Synchronized private fun install(context: Context, native: File): File =
        RandomAccessFile(File(context.buranFilesDir, ".wine-install.lock"), "rw").use { guard ->
            guard.channel.lock().use { installLocked(context, native) }
        }

    private fun installLocked(context: Context, native: File): File {
        val root = File(context.buranFilesDir, "wine-runtime")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val payload = context.assets.open("wine-payload.id").bufferedReader().use { it.readText() }
        if (root.resolve(".payload").takeIf { it.isFile }?.readText() == payload) {
            if (root.resolve(".version").takeIf { it.isFile }?.readText() != stamp) {
                linkRuntime(context, root, native)
                root.resolve(".version").writeText(stamp)
            }
            return root
        }
        val stage = File(context.buranFilesDir, "wine-staging")
        stage.deleteRecursively()
        check(stage.mkdir()) { "Не удалось создать каталог Wine" }
        ZipInputStream(context.assets.open("wine-runtime.zip").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = stage.resolve(entry.name)
                require(file.canonicalPath.startsWith(stage.canonicalPath + "/"))
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile!!.mkdirs()
                    file.outputStream().buffered().use { zip.copyTo(it) }
                }
            }
        }
        // Wine uses wine.inf mtime to decide whether to update an existing prefix.
        val inf = "share/wine/wine.inf"
        val previous = root.resolve(inf)
        if (previous.isFile && previous.readBytes().contentEquals(stage.resolve(inf).readBytes())) {
            check(stage.resolve(inf).setLastModified(previous.lastModified()))
        }
        linkRuntime(context, stage, native)
        stage.resolve(".payload").writeText(payload)
        stage.resolve(".version").writeText(stamp)
        root.deleteRecursively()
        check(stage.renameTo(root)) { "Не удалось установить комплект Wine" }
        return root
    }

    private fun linkRuntime(context: Context, root: File, native: File) {
        for (table in listOf("wine-links.tsv", "wine-display-links.tsv")) {
            context.assets.open(table).bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val (path, library) = line.split('\t')
                    val link = root.resolve(path)
                    require(link.parentFile!!.canonicalPath.startsWith(root.canonicalPath + "/") && '/' !in library)
                    val target = native.resolve(library)
                    check(target.isFile) { "Компонент отсутствует: $library" }
                    link.parentFile!!.mkdirs()
                    val staged = File(link.parentFile, ".${link.name}.new")
                    staged.delete()
                    Os.symlink(target.path, staged.path)
                    Os.rename(staged.path, link.path)
                }
            }
        }
    }

    fun startWindow(
        context: Context, display: Map<String, String>, game: InstalledGame?, desktop: Boolean,
        gameArguments: List<String>, external: ExternalWindowsLaunch? = null, desktopExit: File? = null,
        containerState: File? = null,
        fexDirectory: File? = null,
        dependencies: SteamInstallDependencies? = null,
        onDependencyInstalling: () -> Unit = {},
    ): Process {
        val helper = desktopExit?.let { "Z:" + installExitHelper(context).path.replace('/', '\\') }
        val probe = "buran-window.exe".takeIf { game == null && !desktop }
        val executable = game?.let { "G:\\" + it.exe.replace('/', '\\') } ?: probe?.let { "C:\\$it" }
        val command = if (desktop) wineDesktopCommand(executable, gameArguments, helper)
            else listOfNotNull(helper, checkNotNull(executable)) + gameArguments
        val environment = if (desktopExit == null) display
            else display + ("BURAN_DESKTOP_EXIT" to ("Z:" + desktopExit.path.replace('/', '\\')))
        val prefix = containerState ?: game?.let {
            Containers.directory(context, Containers.selected(context, it.id, WindowsEngine.FEX, external != null))
        }
        return startCommand(context, probe, command, environment, game = game, external = external, containerState = prefix,
            fexDirectory = fexDirectory, dependencies = dependencies, onDependencyInstalling = onDependencyInstalling)
    }

    private fun installExitHelper(context: Context): File {
        val tools = context.buranFilesDir.resolve("wine-tools").apply { mkdirs() }
        val helper = tools.resolve("buran-desktop-exit.exe")
        val staged = File.createTempFile("buran-exit-", ".tmp", tools)
        try {
            context.assets.open(helper.name).use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            Os.rename(staged.path, helper.path)
            return helper
        } finally {
            staged.delete()
        }
    }

    fun stopGame(context: Context, prefix: File, guest: Process) {
        if (guest.isAlive) guest.destroyForcibly()
        check(guest.waitFor(2, TimeUnit.SECONDS)) { "Wine не завершился после запроса остановки" }
        stopServer(context, prefix)
    }

    private fun stopServer(context: Context, prefix: File) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val root = context.buranFilesDir.resolve("wine-runtime")
        val server = root.resolve("bin/wineserver")
        fun runServerCommand(option: String): Int {
            val command = ProcessBuilder(server.path, option).apply {
                environment().putAll(mapOf(
                    "BURAN_WINE_ROOT" to root.path, "WINEPREFIX" to prefix.path,
                    "LD_PRELOAD" to native.resolve("libburan_winepaths.so").path,
                    "BURAN_WINE_NATIVE_DIR" to native.path,
                    "LD_LIBRARY_PATH" to "${root.resolve("lib/wine/aarch64-unix").path}:${native.path}",
                    "HOME" to context.buranFilesDir.path, "PREFIX" to root.path, "TMPDIR" to context.buranCacheDir.path,
                ))
                redirectErrorStream(true)
                redirectOutput(File("/dev/null"))
            }.start()
            if (!command.waitFor(2, TimeUnit.SECONDS)) {
                command.destroyForcibly()
                error("Wine-сервер не завершился после запроса остановки")
            }
            return command.exitValue()
        }

        // -k returns 1 when the prefix has no active server. -w verifies shutdown in either case.
        val stop = runServerCommand("-k")
        check(stop in 0..1 && runServerCommand("-w") == 0) { "Не удалось завершить Wine-сервер игры" }
    }

    private fun prepareFiles(context: Context, prefix: File, fexDirectory: File? = null): File {
        val root = install(context, File(context.applicationInfo.nativeLibraryDir))
        val drive = File(prefix, "drive_c").apply { mkdirs() }
        val system = File(drive, "windows/system32").apply { mkdirs() }
        val fonts = File(drive, "windows/Fonts").apply { mkdirs() }
        val font = File(fonts, "DejaVuSans.ttf")
        if (!font.isFile) context.assets.open("wine-display-font.ttf").use { input ->
            font.outputStream().use { input.copyTo(it) }
        }
        val fex = fexDirectory ?: WcpFiles.activeFexDirectory(context) ?: root.resolve("fex/system32")
        for (name in listOf("libwow64fex.dll", "libarm64ecfex.dll")) {
            val temp = File(system, ".$name.new")
            fex.resolve(name).copyTo(temp, overwrite = true)
            Os.rename(temp.path, File(system, name).path)
        }
        val devices = File(prefix, "dosdevices").apply { mkdirs() }
        for ((letter, dest) in mapOf("c:" to "../drive_c", "z:" to "/")) {
            val link = File(devices, letter)
            if (!link.exists()) Os.symlink(dest, link.path)
        }
        return root
    }

    fun needsPreparation(prefix: File): Boolean = !prefix.resolve("system.reg").isFile ||
        listOf("kernel32.dll", "kernelbase.dll", "wineboot.exe").any {
            val dll = prefix.resolve("drive_c/windows/system32/$it")
            !dll.isFile || dll.length() < 64
        }

    fun prepareContainer(context: Context, prefix: File, fexDirectory: File? = null) {
        if (needsPreparation(prefix)) bootstrap(context, prepareFiles(context, prefix, fexDirectory), prefix)
    }

    private fun startCommand(context: Context, probe: String?, guestCommand: List<String>,
                             display: Map<String, String>, useTurnip: Boolean = false,
                             game: InstalledGame? = null, external: ExternalWindowsLaunch? = null,
                             containerState: File? = null, fexDirectory: File? = null,
                             dependencies: SteamInstallDependencies? = null,
                             onDependencyInstalling: () -> Unit = {}): Process {
        val prefix = containerState ?: context.buranFilesDir.resolve(prefixName)
        val root = prepareFiles(context, prefix, fexDirectory)
        if (needsPreparation(prefix)) bootstrap(context, root, prefix)
        val drive = prefix.resolve("drive_c")
        val devices = prefix.resolve("dosdevices")
        if (probe != null) context.assets.open(probe).use { input ->
            File(drive, probe).outputStream().use { input.copyTo(it) }
        }
        val workingDirectory = if (game != null) {
            val installation = external?.directory ?: game.directory(context)
            val entry = installation.resolve(game.exe)
            check(entry.isFile) { "В установке отсутствует ${game.exe}" }
            val gameDrive = File(devices, "g:")
            if (Files.isSymbolicLink(gameDrive.toPath()) &&
                gameDrive.canonicalFile != installation.canonicalFile
            ) {
                stopServer(context, prefix)
                val link = devices.resolve(".g-${UUID.randomUUID()}")
                Os.symlink(installation.path, link.path)
                try {
                    Os.rename(link.path, gameDrive.path)
                } finally {
                    link.delete()
                }
            } else if (!Files.exists(gameDrive.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Os.symlink(installation.path, gameDrive.path)
            }
            check(gameDrive.canonicalFile == installation.canonicalFile) { "Путь диска игры изменился" }
            external?.workingDirectory ?: entry.parentFile!!
        } else drive
        val log = if (display.isEmpty()) "windows" else "window"
        val turnip = if (useTurnip) TurnipVulkan.environment(context) else emptyMap()
        val environment = external?.environment.orEmpty() + display + turnip
        if (dependencies != null && external != null) {
            try {
                dependencies.runBionic(external.directory, context.buranFilesDir.resolve("steam-dependency-status.txt"), onDependencyInstalling) {
                        directory, arguments, output, timeout ->
                    val process = startWine(context, root, prefix, arguments, directory, environment, "steam-dependency", output)
                    try {
                        if (process.waitFor(timeout, TimeUnit.SECONDS)) process.exitValue() else -1
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
            } catch (error: Exception) {
                runCatching { stopServer(context, prefix) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        return startWine(context, root, prefix, guestCommand, workingDirectory, environment, log)
    }

    private fun startWine(
        context: Context, root: File, prefix: File, args: List<String>, cwd: File,
        overrides: Map<String, String>, output: String, stdout: File? = null,
    ): Process {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val libraryPath = listOfNotNull(overrides["BURAN_TURNIP_LOADER_DIR"],
            root.resolve("lib/wine/aarch64-unix").path, native.path).joinToString(":")
        val baseEnvironment = mapOf(
            "BURAN_WINE_ROOT" to root.path, "WINEPREFIX" to prefix.path,
            "LD_PRELOAD" to native.resolve("libburan_winepaths.so").path,
            "BURAN_WINE_NATIVE_DIR" to native.path,
            "LD_LIBRARY_PATH" to libraryPath, "HOME" to context.buranFilesDir.path, "PREFIX" to root.path,
            "TMPDIR" to context.buranCacheDir.path, "HODLL" to "libwow64fex.dll",
            "WINESERVER" to root.resolve("bin/wineserver").path,
            "WINEDLLPATH" to root.resolve("lib/wine").path,
            "PROTON_NO_NTSYNC" to "1", "WINEDLLOVERRIDES" to "winemenubuilder.exe=d",
            "WINEESYNC" to "0", "WINEFSYNC" to "0", "WINEDEBUG" to "-all,err+all"
        )
        val command = listOf(native.resolve("libburan.so").path, "run-in", prefix.resolve("drive_c").path, cwd.path,
            root.resolve("lib/wine/aarch64-unix/wine").path) + args
        return ProcessBuilder(command).apply {
            environment().putAll(baseEnvironment)
            environment().putAll(overrides)
            redirectOutput(stdout ?: context.buranFilesDir.resolve("$output-stdout.txt"))
            redirectError(context.buranFilesDir.resolve("$output-stderr.txt"))
        }.start()
    }

    private fun bootstrap(context: Context, root: File, prefix: File) {
        // An interrupted first run can leave system.reg without Wine's builtin modules.
        val boot = startWine(context, root, prefix, listOf("C:\\windows\\system32\\wineboot.exe", "--update"),
            prefix.resolve("drive_c"), mapOf(
                "WINEBOOTSTRAPMODE" to "1",
                "WINEDLLOVERRIDES" to "winemenubuilder.exe,mscoree,mshtml,winex11.drv,winewayland.drv=d",
            ), "fex-prefix")
        val result = try {
            if (boot.waitFor(90, TimeUnit.SECONDS)) boot.exitValue() else null
        } catch (error: InterruptedException) {
            runCatching { stopGame(context, prefix, boot) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        val shutdown = runCatching { stopGame(context, prefix, boot) }.exceptionOrNull()
        check(result == 0 && shutdown == null && !needsPreparation(prefix)) {
            val detail = when {
                result == null -> "wineboot не завершился за 90 секунд"
                result != 0 -> "wineboot завершился с кодом $result"
                shutdown != null -> shutdown.localizedMessage
                else -> "Wine не создал системный реестр или DLL"
            }
            val errors = context.buranFilesDir.resolve("fex-prefix-stderr.txt").takeIf(File::isFile)
                ?.readLines()?.takeLast(6)?.joinToString("\n").orEmpty()
            "Не удалось подготовить префикс FEX. $detail\n$errors"
        }
    }

    fun runVulkan(context: Context): String {
        val token = UUID.randomUUID().toString()
        val command = listOf("C:\\windows\\system32\\cmd.exe", "/d", "/c",
            "C:\\buran-vulkan.exe", token, ">", "NUL")
        val process = startCommand(context, "buran-vulkan.exe", command,
            mapOf(
                "BURAN_TURNIP_HEADLESS" to "1",
                "WINEDLLOVERRIDES" to "winemenubuilder.exe,mscoree,mshtml,winex11.drv,winewayland.drv=d",
            ), useTurnip = true)
        try {
            check(process.waitFor(120, TimeUnit.SECONDS)) { "Windows не завершил проверку Vulkan за 120 секунд" }
            val report = context.buranFilesDir.resolve("$prefixName/drive_c/buran-vulkan-report.txt")
            val lines = report.takeIf { it.isFile }?.readLines().orEmpty()
            check("TOKEN=$token" in lines) { "Windows не вернул отчёт этого запуска Vulkan (код ${process.exitValue()})" }
            val details = lines.filterNot { it.startsWith("TOKEN=") }.joinToString("\n")
            check(process.exitValue() == 0 && "BURAN_VULKAN_OK" in lines && "GPU_FILL=0x42555241" in lines) {
                "Проверка Vulkan завершилась с кодом ${process.exitValue()}\n$details"
            }
            check("DRIVER_ID=18" in lines) { "Windows использует другой Vulkan-драйвер\n$details" }
            return "Windows выполнил команду GPU через Turnip\n$details"
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun run(context: Context): String {
        val token = UUID.randomUUID().toString()
        val reportName = "buran-console-$token.txt"
        val report = File(context.buranFilesDir, "$prefixName/drive_c/$reportName")
        val errors = File(context.buranFilesDir, "windows-stderr.txt")
        try {
            // Let cmd provide the Windows stdout handle inside Wine.
            val command = listOf("C:\\windows\\system32\\cmd.exe", "/d", "/c",
                "C:\\buran-console.exe", token, ">", "C:\\$reportName")
            val process = startCommand(context, "buran-console.exe", command, emptyMap())
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Wine не завершился за 120 секунд")
            }
            val stdout = if (report.isFile) report.readText().trim() else ""
            val code = process.exitValue()
            val ok = code == 37 && stdout.lines().containsAll(listOf("BURAN_WINDOWS_X64_OK", "TOKEN=$token"))
            val result = if (ok) "Windows x86-64 выполнен" else "Проверка Windows завершилась ошибкой"
            return "$result\n$stdout\nКод завершения: $code" +
                if (ok) "" else "\n" + errors.useLines { lines -> lines.take(5).joinToString("\n") }
        } finally {
            report.delete()
        }
    }
}
