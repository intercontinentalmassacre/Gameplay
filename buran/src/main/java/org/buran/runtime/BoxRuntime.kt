package org.buran.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Process.myUid
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.buran.runtime.display.GamescopeGuestInstall
import org.buran.runtime.display.GamescopeRuntime
import java.io.File
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object BoxRuntime {
    private data class GameProcess(val pidFile: File, val proot: File, val pauseFile: File?) {
        val parkedFile = pauseFile?.let { File(it.path + ".parked") }
        var pauseFailureLogged = false

        fun pauseNotConfirmed(reason: String): Boolean {
            if (!pauseFailureLogged) Log.w("BuranBox", reason)
            pauseFailureLogged = true
            return false
        }
    }

    private val pauseSignals by lazy { System.loadLibrary("buran_guest_pause") }
    @JvmStatic private external fun stopTracee(pid: Int, tid: Int)
    private val gameProcesses = ConcurrentHashMap<Process, GameProcess>()
    private const val GUEST_HOME = "/home/buran"
    private const val MONO_PROBE_STATE = "box-state/mono"

    fun monoInstalled(state: File): Boolean {
        val marker = state.resolve("prefix/.buran-mono-installed")
        val mono = state.resolve("prefix/drive_c/windows/mono/mono-2.0")
        return marker.isFile && marker.readText().trim() == WineMono.box64.fileName &&
            mono.resolve("bin/libmono-2.0-x86_64.dll").isFile && mono.resolve("lib/mono/4.5/mscorlib.dll").isFile
    }

    fun run(context: Context, bits: Int): String {
        val label = if (bits == 32) "Box86" else "Box64"
        val token = UUID.randomUUID().toString()
        val stdout = context.buranFilesDir.resolve("box-$bits-stdout.txt")
        val stderr = context.buranFilesDir.resolve("box-$bits-stderr.txt")
        val exe = if (bits == 32) "buran-console-x86.exe" else "buran-console.exe"
        val process = start(context, bits, exe, listOf(token), emptyMap(), stdout, stderr)
        try {
            check(process.waitFor(180, TimeUnit.SECONDS)) { "$label не завершился за 180 секунд" }
        } finally {
            stop(process)
        }
        val code = process.exitValue()
        val output = stdout.readText().trim()
        val marker = if (bits == 32) "BURAN_WINDOWS_X86_OK" else "BURAN_WINDOWS_X64_OK"
        val success = code == 37 && output.lines().containsAll(listOf(marker, "TOKEN=$token"))
        val platform = if (bits == 32) "x86" else "x86-64"
        val result = if (success) "$label выполнил Windows $platform" else "$label: ошибка выполнения Windows"
        return "$result\n$output\nКод завершения: $code" +
            if (success) "" else "\n" + stderr.readText().lineSequence().takeLastLines()
    }

    fun startWindow(
        context: Context, bits: Int, display: Map<String, String>, game: InstalledGame?, desktop: Boolean,
        requiresMono: Boolean = false, dxvk: Boolean = false,
        gameArguments: List<String> = emptyList(),
        external: ExternalWindowsLaunch? = null, desktopExit: File? = null,
        containerState: File? = null,
        installedDxvk: DxvkComponents.Installed? = null,
        box64Package: File? = null,
        dependencies: SteamInstallDependencies? = null,
        dependencyProgress: File? = null,
    ): Process {
        val state = containerState ?: game?.let {
            val engine = if (bits == 64) WindowsEngine.BOX64 else WindowsEngine.BOX86
            Containers.directory(context, Containers.selected(context, it.id, engine, external != null))
        }
        val probe = if (game != null || desktop) null else if (bits == 32) "buran-window-x86.exe" else "buran-window.exe"
        val monoMsi = if (requiresMono) {
            checkNotNull(WineMono.box64.cached(context)) { "Пакет Wine Mono отсутствует. Загрузите его перед запуском." }
        } else if (bits == 64 && (game != null || desktop)) {
            WineMono.box64.enabled(context)
        } else null
        val log = if (dxvk) "dxvk" else "window"
        return start(context, bits, probe, gameArguments, display,
            context.buranFilesDir.resolve("$log-stdout.txt"), context.buranFilesDir.resolve("$log-stderr.txt"), game, desktop,
            dxvk = dxvk, monoMsi = monoMsi, external = external, desktopExit = desktopExit, containerState = state,
            installedDxvk = installedDxvk, box64Package = box64Package, dependencies = dependencies,
            dependencyProgress = dependencyProgress)
    }

    fun runMono(context: Context): String {
        val component = WineMono.box64
        val installer = checkNotNull(component.cached(context)) { "Сначала загрузите Wine Mono ${component.version}" }
        val token = UUID.randomUUID().toString()
        val process = start(
            context, 64, "buran-managed.exe", listOf(token), emptyMap(),
            context.buranFilesDir.resolve("mono-stdout.txt"), context.buranFilesDir.resolve("mono-stderr.txt"),
            monoMsi = installer, statePath = MONO_PROBE_STATE,
        )
        try {
            check(process.waitFor(180, TimeUnit.SECONDS)) { "Проверка Wine Mono заняла больше 180 секунд" }
        } finally {
            stop(process)
        }
        val report = context.buranFilesDir.resolve("$MONO_PROBE_STATE/programs/buran-mono-report.txt")
            .takeIf(File::isFile)?.readLines().orEmpty()
        val version = report.firstOrNull { it.startsWith("version=") }?.substringAfter('=')
        check(process.exitValue() == 0 && !version.isNullOrBlank() &&
            report.containsAll(listOf("BURAN_MANAGED_OK", "runtime=Mono", "token=$token"))) {
            "Проверка Wine Mono завершилась с кодом ${process.exitValue()}. Журнал mono-stderr.txt."
        }
        return "Wine Mono ${component.version} выполнил приложение .NET Framework в Box64\n$version"
    }

    fun startDxvk(context: Context, display: Map<String, String>): Process {
        return start(context, 64, "buran-d3d11.exe", emptyList(), display,
            context.buranFilesDir.resolve("dxvk-stdout.txt"), context.buranFilesDir.resolve("dxvk-stderr.txt"), dxvk = true)
    }

    fun setPaused(process: Process, paused: Boolean): Boolean {
        val game = gameProcesses[process] ?: return false
        val pauseFile = game.pauseFile ?: return false
        val parkedFile = checkNotNull(game.parkedFile)
        return synchronized(game) {
            if (!process.isAlive || gameProcesses[process] != game) return@synchronized false
            if (!paused) return@synchronized resumeGuest(game)
            val owner = prootPid(game)
                ?: return@synchronized game.pauseNotConfirmed("Pause: waiting for the game PRoot")
            var tasks = traceeTasks(owner)
            if (tasks.isEmpty()) {
                return@synchronized game.pauseNotConfirmed("Pause: no tasks found for PRoot pid=$owner")
            }
            pauseSignals
            if (!pauseFile.exists()) {
                if (parkedFile.exists() || !pauseFile.createNewFile()) return@synchronized false
            }
            val nudged = mutableSetOf<Int>()
            repeat(4) {
                val parked = readParked(parkedFile)
                if (tasks.isNotEmpty() && tasks.all { (_, tid) -> tid in parked }) {
                    game.pauseFailureLogged = false
                    return@synchronized true
                }
                for ((pid, tid) in tasks) {
                    if (tid !in parked && nudged.add(tid)) stopTracee(pid, tid)
                }
                SystemClock.sleep(30)
                tasks = traceeTasks(owner)
            }
            val parked = readParked(parkedFile)
            val confirmed = tasks.count { (_, tid) -> tid in parked }
            if (tasks.isNotEmpty() && confirmed == tasks.size) {
                game.pauseFailureLogged = false
                return@synchronized true
            }
            resumeGuest(game)
            game.pauseNotConfirmed("Pause: PRoot pid=$owner confirmed $confirmed/${tasks.size} tasks")
        }
    }

    private fun readParked(file: File): Set<Int> =
        runCatching { file.readLines().mapNotNull(String::toIntOrNull).toSet() }.getOrDefault(emptySet())

    private fun traceeProcesses(owner: Int): List<Pair<Int, File>> =
        File("/proc").listFiles().orEmpty().mapNotNull { directory ->
            val pid = directory.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == owner || !runCatching {
                    directory.resolve("status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("TracerPid:") }
                            ?.substringAfter(':')?.trim()?.toIntOrNull() == owner
                    }
                }.getOrDefault(false)
            ) return@mapNotNull null
            pid to directory
        }

    private fun traceeTasks(owner: Int): List<Pair<Int, Int>> =
        traceeProcesses(owner).flatMap { (pid, directory) ->
            directory.resolve("task").list().orEmpty().mapNotNull { task ->
                task.toIntOrNull()?.let { tid -> pid to tid }
            }
        }

    fun mangoHudGuestPids(process: Process, gameExe: String): List<Int> {
        val owner = gameProcesses[process]?.let(::prootPid) ?: return emptyList()
        val exeName = gameExe.substringAfterLast('/').take(15)
        val (main, children) = traceeProcesses(owner).mapNotNull { (pid, directory) ->
            val name = runCatching { directory.resolve("comm").readText().trimEnd('\n') }.getOrNull()
                ?: return@mapNotNull null
            if (!name.equals(exeName, ignoreCase = true) &&
                (name.lowercase() in wineProcesses || name.startsWith("buran-desktop-") ||
                    !name.endsWith(".exe", ignoreCase = true) && name.length != 15)
            ) return@mapNotNull null
            // An OpenGL game can initialize an internal Zink Vulkan swapchain without
            // presenting MangoHud to its Wine game window. Require Wine's Vulkan bridge.
            val usesWineVulkan = runCatching {
                directory.resolve("maps").useLines { mappings ->
                    mappings.any { mapping ->
                        val library = mapping.substringAfterLast('/').substringBefore(' ')
                        library == "winevulkan.so" || library == "winevulkan.dll"
                    }
                }
            }.getOrDefault(false)
            if (!usesWineVulkan) return@mapNotNull null
            pid to name
        }.partition { (_, name) -> name.equals(exeName, ignoreCase = true) }
        return (main + children).map { (pid, _) -> pid }
    }

    private val wineProcesses = setOf(
        "wineboot.exe", "explorer.exe", "services.exe", "winedevice.exe", "svchost.exe",
        "plugplay.exe", "rpcss.exe", "conhost.exe", "start.exe",
    )

    private fun resumeGuest(game: GameProcess): Boolean {
        val requested = game.pauseFile ?: return true
        if (requested.exists() && !requested.delete()) return false
        repeat(10) {
            if (game.parkedFile?.exists() != true) return true
            SystemClock.sleep(10)
        }
        return game.parkedFile?.exists() != true
    }

    fun stop(process: Process, releaseDisplay: (() -> Unit)? = null) {
        val gameProcess = gameProcesses[process]
        if (gameProcess != null) synchronized(gameProcess) {
            gameProcesses.remove(process, gameProcess)
            if (process.isAlive && !resumeGuest(gameProcess)) {
                Log.w("BuranBox", "PRoot did not confirm resuming before the Wine stop request")
            }
        }
        var interrupted = Thread.interrupted()
        fun waitForExit(seconds: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        }
        try {
            if (!process.isAlive) return
            val startedAt = System.nanoTime()
            runCatching {
                process.outputStream.use { input ->
                    if (process.isAlive) {
                        input.write("BURAN_STOP\n".toByteArray())
                        input.flush()
                    }
                }
            }
            var exited = waitForExit(3)
            if (!exited && releaseDisplay != null) {
                Log.w("BuranBox", "Wine still active after stop request, closing Xwayland")
                releaseDisplay()
                exited = waitForExit(2)
            }
            if (exited) {
                if (releaseDisplay != null) {
                    val code = process.exitValue()
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                    Log.i("BuranBox", "Wine stop exit=$code elapsedMs=$elapsed")
                    check(code == 0 || code == 143) { "Wine не подтвердил остановку (код $code)" }
                }
                return
            }
            if (gameProcess == null) {
                process.destroyForcibly()
                return
            }
            signalProot(process, gameProcess, OsConstants.SIGQUIT)
            if (!waitForExit(2)) {
                signalProot(process, gameProcess, OsConstants.SIGKILL)
                check(waitForExit(2)) { "Не удалось завершить процессы Wine" }
            }
            error("PRoot остановлен принудительно. Wine не подтвердил завершение")
        } finally {
            gameProcess?.pidFile?.delete()
            gameProcess?.pauseFile?.delete()
            gameProcess?.parkedFile?.delete()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun prootPid(game: GameProcess): Int? = runCatching {
        val pid = game.pidFile.readText().trim().toInt()
        require(pid > 0)
        val expected = "${game.proot.path}\u0000".toByteArray()
        val actual = ByteArray(expected.size)
        DataInputStream(File("/proc/$pid/cmdline").inputStream()).use { it.readFully(actual) }
        pid.takeIf { actual.contentEquals(expected) }
    }.getOrNull()

    private fun signalProot(process: Process, game: GameProcess, signal: Int) {
        try {
            val pid = prootPid(game) ?: return
            if (process.isAlive) {
                Log.w("BuranBox", "Stopping PRoot pid=$pid signal=$signal")
                Os.kill(pid, signal)
            }
        } catch (error: Exception) {
            if (process.isAlive) Log.w("BuranBox", "Could not signal owned PRoot process", error)
        }
    }

    private fun start(
        context: Context, bits: Int, probe: String?, args: List<String>, display: Map<String, String>,
        stdout: File, stderr: File, game: InstalledGame? = null, desktop: Boolean = false,
        dxvk: Boolean = false, monoMsi: File? = null,
        statePath: String = if (dxvk) "box-state/dxvk" else "box-state/$bits",
        external: ExternalWindowsLaunch? = null, desktopExit: File? = null,
        containerState: File? = null,
        installedDxvk: DxvkComponents.Installed? = null,
        box64Package: File? = null,
        dependencies: SteamInstallDependencies? = null,
        dependencyProgress: File? = null,
    ): Process {
        require(bits == 32 || bits == 64)
        if (dxvk) {
            check(bits == 64 && !display["DISPLAY"].isNullOrEmpty() && !display["TMPDIR"].isNullOrEmpty()) {
                "Для DXVK требуется Box64 и Xwayland"
            }
        }
        check(Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")) { "Box требует Android ARM64" }
        if (bits == 32) {
            check(Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")) { "Box86 требует ARMv7 (AArch32)" }
        }
        val boxRoot = install(context, bits)
        val root = if (dxvk) GamescopeGuestInstall.rootfs(context) else boxRoot
        val native = File(context.applicationInfo.nativeLibraryDir)
        val runner = native.resolve("libburan.so")
        val proot = native.resolve("libburan_gamescope_proot.so")
        val loader = native.resolve("libburan_gamescope_loader.so")
        val loader32 = native.resolve("libburan_box_loader32.so")
        val talloc = native.resolve("libburan_gamescope_talloc.so")
        check(listOf(runner, proot, loader, talloc).all(File::isFile) && (bits == 64 || loader32.isFile)) {
            "В APK отсутствует загрузчик PRoot для Box"
        }
        val state = containerState ?: context.buranFilesDir.resolve(statePath)
        state.mkdirs()
        val drive = state.resolve("programs").apply { mkdirs() }
        if (probe != null) {
            context.assets.open(probe).use { input -> drive.resolve(probe).outputStream().use { input.copyTo(it) } }
        }
        val selectedDxvk = if (dxvk && game != null) installedDxvk?.files(bits).orEmpty() else emptyMap()
        val dxvkSource = if (dxvk && game != null) {
            if (selectedDxvk.isEmpty()) DxvkComponents.source(context) else {
                state.resolve("dxvk-payload").apply {
                    mkdirs()
                    DxvkComponents.copyInstalled(selectedDxvk, this)
                }
            }
        } else null
        if (dxvk && game == null) DxvkComponents.install(context, drive)
        val installation = game?.let {
            (external?.directory ?: it.directory(context)).also { directory ->
                check(directory.resolve(it.exe).isFile) { "В установке отсутствует ${it.exe}" }
                require(external == null || ':' !in directory.path) { "Каталог установки содержит двоеточие" }
            }
        }
        val cache = context.buranCacheDir.resolve(if (dxvk) "box/dxvk" else "box/$bits").apply { mkdirs() }
        val libs = cache.resolve("lib").apply { mkdirs() }
        val tallocLink = libs.resolve("libtalloc.so.2")
        synchronized(this) {
            if (!tallocLink.isFile || tallocLink.canonicalPath != talloc.canonicalPath) {
                tallocLink.delete()
                Os.symlink(talloc.path, tallocLink.path)
            }
        }
        val tmp = display["TMPDIR"] ?: cache.resolve("tmp").apply { mkdirs() }.path
        val programs = "$GUEST_HOME/programs"
        val guestGame = game?.let { "$programs/${it.exe}" }
        val guestExe = guestGame?.let { "Z:" + it.replace('/', '\\') } ?: probe?.let { "$programs/$it" }
        val guestCwd = if (external != null) {
            val relative = external.relativeWorkingDirectory
            if (relative.isEmpty()) programs else "$programs/$relative"
        } else guestGame?.substringBeforeLast('/') ?: programs
        val helper = desktopExit?.let {
            val name = if (bits == 32) "buran-desktop-exit-x86.exe" else "buran-desktop-exit.exe"
            context.assets.open(name).use { input -> state.resolve(name).outputStream().use { input.copyTo(it) } }
            "Z:" + "$GUEST_HOME/$name".replace('/', '\\')
        }
        val wineCommand = when {
            desktop -> wineDesktopCommand(guestExe, args, helper)
            helper != null -> listOf(helper, checkNotNull(guestExe)) + args
            // Wait inside Wine for the managed process's Win32 exit code.
            monoMsi != null -> listOf("start.exe", "/b", "/wait", "/unix", guestGame ?: checkNotNull(guestExe)) + args
            else -> listOf(checkNotNull(guestExe)) + args
        }
        val hook = if (game != null && dxvk) {
            native.resolve("libburan_guest_pause.so").also {
                check(it.isFile) { "В APK отсутствует модуль паузы Box64" }
            }
        } else null
        val pidFile = game?.let { File.createTempFile("box-", ".pid", cache) }
        val pauseFile = pidFile?.takeIf { hook != null }?.let { File(it.path + ".pause") }
        val command = mutableListOf(runner.path, "run", state.path)
        if (hook != null && pauseFile != null) {
            command.addAll(listOf(
                "/system/bin/sh", "-c",
                "export LD_PRELOAD=\"\$1\" BURAN_GUEST_PAUSE_FILE=\"\$2\"; shift 2; exec \"\$@\"",
                "buran-guest-pause", hook.path, pauseFile.path,
            ))
        }
        command.addAll(listOf(proot.path, "--kill-on-exit", "-i", "${myUid()}:${Os.getgid()}",
            "-r", root.path, "-w", guestCwd))
        fun bind(spec: String) { command.addAll(listOf("-b", spec)) }
        for (path in listOf("/dev", "/proc")) bind(path)
        bind("${state.path}:$GUEST_HOME")
        if (installation != null) bind("${installation.path}:$programs")
        external?.steamUserDirectory?.let { profile ->
            for (relative in listOf("AppData/LocalLow", "AppData/Local", "AppData/Roaming", "Documents", "Saved Games")) {
                val source = profile.resolve(relative).canonicalFile
                if (source.toPath().startsWith(profile.toPath()) && source.isDirectory && ':' !in source.path) {
                    bind("${source.path}:$GUEST_HOME/prefix/drive_c/users/buran/$relative")
                }
            }
        }
        external?.steamRemoteDirectory?.let { remote ->
            val account = checkNotNull(remote.parentFile?.parentFile).name
            val userdata = "$GUEST_HOME/prefix/drive_c/Program Files (x86)/Steam/userdata"
            bind("${remote.path}:$userdata/$account/${external.steamAppId}/remote")
        }
        bind("$tmp:/tmp")
        if (bits == 64 && box64Package != null) {
            val staged = File.createTempFile(".box64-", ".bin", cache)
            val translator = cache.resolve("selected-box64")
            try {
                box64Package.copyTo(staged, overwrite = true)
                Os.chmod(staged.path, 448)
                Files.move(staged.toPath(), translator.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                staged.delete()
            }
            bind("${translator.path}:/usr/local/bin/box64")
        }
        val wine = if (bits == 32) "/opt/buran/wine86" else "/opt/buran/wine64"
        val wineLoader = if (bits == 32) "wine" else "wine64"
        val wineServer = if (bits == 32) "wineserver.i386" else "wineserver"
        val box = if (bits == 32) "BOX86" else "BOX64"
        val arch = if (bits == 32) "i386" else "x86_64"
        val archDir = if (bits == 32) "arm-linux-gnueabihf" else "aarch64-linux-gnu"
        val env = mutableMapOf(
            "HOME" to GUEST_HOME,
            "USER" to "buran",
            "LOGNAME" to "buran",
            "PATH" to "$wine/bin:/usr/local/bin:/bin:/usr/bin",
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "WINEPREFIX" to "$GUEST_HOME/prefix",
            "WINEARCH" to (if (bits == 32) "win32" else "win64"),
            "WINEDEBUG" to "-all,err+all",
            "WINEDLLOVERRIDES" to "winemenubuilder.exe=d;mscoree=d;mshtml=d",
            "${box}_NOBANNER" to "1",
            "${box}_LD_LIBRARY_PATH" to "$wine/lib/wine/$arch-unix:/lib/$arch-linux-gnu:/usr/lib/$arch-linux-gnu",
            "LD_LIBRARY_PATH" to "/lib/$archDir:/usr/lib/$archDir",
        )
        if (dependencies != null && installation != null) {
            val shared = installation.parentFile.resolve("Steamworks Shared").canonicalFile
            val guestShared = "$GUEST_HOME/steamworks-shared"
            if (dependencies.usesDirectory(shared)) {
                state.resolve("steamworks-shared").mkdirs()
                bind("${shared.path}:$guestShared")
            }
            val plan = state.resolve("steam-dependencies.plan")
            fun dos(path: String) = "Z:" + path.replace('/', '\\')
            dependencies.writeBoxPlan(plan, dos(programs), is64Bit = bits == 64) { file ->
                val root = if (file.toPath().startsWith(installation.toPath())) installation else shared
                val guestRoot = if (root == installation) programs else guestShared
                dos("$guestRoot/${file.relativeTo(root).invariantSeparatorsPath}")
            }
            env["BURAN_STEAM_DEPENDENCIES"] = "$GUEST_HOME/${plan.name}"
            dependencyProgress?.let {
                val rootTmp = File(tmp).canonicalFile.toPath()
                val marker = it.canonicalFile.toPath()
                require(marker.startsWith(rootTmp) && marker != rootTmp)
                env["BURAN_STEAM_DEPENDENCY_PROGRESS"] = "/tmp/${rootTmp.relativize(marker)}"
            }
        }
        if (dxvk || dependencies != null) {
            val sessionScript = cache.resolve("buran-box-session")
            context.assets.open("buran-box-session.sh").use { input ->
                sessionScript.outputStream().use { input.copyTo(it) }
            }
            bind("${sessionScript.path}:/usr/local/bin/buran-box-session")
        }
        if (monoMsi != null) {
            val guestInstaller = "$GUEST_HOME/${monoMsi.name}"
            bind("${monoMsi.path}:$guestInstaller")
            env["BURAN_MONO_MSI"] = "Z:" + guestInstaller.replace('/', '\\')
            env["BURAN_MONO_INSTALLER"] = monoMsi.name
        }
        if (monoMsi != null || (bits == 64 && state.resolve("prefix/.buran-mono-installed").isFile)) {
            env["WINEDLLOVERRIDES"] = "winemenubuilder.exe=d;mshtml=d"
        }
        if (!dxvk && containerState != null && bits == 64 &&
            (state.name == "box64-dxvk" || state.resolve("dxvk").isDirectory)
        ) {
            // Wine can load its builtins even when DXVK's native DLLs are still in system32.
            env["WINEDLLOVERRIDES"] = "${env.getValue("WINEDLLOVERRIDES")};d3d8,d3d9,d3d10core,d3d11,dxgi=b"
        }
        if (dxvk) {
            bind("/sys")
            bind("${cache.resolve("shm").apply { mkdirs() }.path}:/dev/shm")
            synchronized(this) {
                GamescopeRuntime.kgslBinds(cache).forEach(::bind)
            }
            bind("${boxRoot.resolve("opt/buran/wine64").path}:$wine")
            if (box64Package == null) bind("${boxRoot.resolve("usr/local/bin/box64").path}:/usr/local/bin/box64")
            bind("${boxRoot.resolve("usr/lib/locale").path}:/usr/lib/locale")
            bind("${boxRoot.resolve("lib/x86_64-linux-gnu").path}:/opt/buran/lib/x86_64-linux-gnu")
            bind("${boxRoot.resolve("usr/lib/x86_64-linux-gnu").path}:/usr/lib/x86_64-linux-gnu")
            // Native Vulkan and X11 share the Gamescope root's glibc and libraries.
            env["LD_LIBRARY_PATH"] = "/usr/lib"
            env["BOX64_LD_LIBRARY_PATH"] =
                "$wine/lib/wine/x86_64-unix:/opt/buran/lib/x86_64-linux-gnu:/usr/lib/x86_64-linux-gnu"
            env["BOX64_LOG"] = "1"
            env["WINEDEBUG"] = "-all,err+all,trace+loaddll"
            if (game == null) {
                env["WINEDLLOVERRIDES"] = "${env.getValue("WINEDLLOVERRIDES")};d3d11,dxgi=n"
            } else {
                bind("${boxRoot.resolve("bin/busybox").path}:/usr/local/bin/busybox")
                state.resolve("dxvk").mkdirs()
                bind("${checkNotNull(dxvkSource).path}:$GUEST_HOME/dxvk")
                env["BURAN_DXVK_DLLS"] = "$GUEST_HOME/dxvk"
                env["BURAN_DXVK_NAMES"] = selectedDxvk.keys.ifEmpty { setOf("d3d11.dll", "dxgi.dll") }.joinToString(" ")
                env["BURAN_DXVK_OVERRIDES"] = DxvkComponents.overrides(selectedDxvk.keys.ifEmpty { setOf("d3d11.dll", "dxgi.dll") })
                // Gameplay owns this memory file when the runtime shares its Android UID.
                val controller = PhysicalGamepad.gameplaySharedFile(context)
                val localController = display["XAUTHORITY"]?.let { File(it).parentFile }
                    ?.resolve(PhysicalGamepad.FILE_NAME)
                if (controller != null) {
                    val guestController = "/tmp/buran-gameplay-pad.mem"
                    bind("${controller.path}:$guestController")
                    env["BURAN_GAMEPAD_SHM"] = guestController
                } else if (localController?.isFile == true && localController.length() == PhysicalGamepad.SHARED_SIZE) {
                    val relative = localController.toPath().normalize().let { path ->
                        val tmpPath = Paths.get(tmp).normalize()
                        require(path.startsWith(tmpPath) && path != tmpPath)
                        tmpPath.relativize(path)
                    }
                    env["BURAN_GAMEPAD_SHM"] = "/tmp/$relative"
                    env["BURAN_GAMEPAD_LOCAL"] = "1"
                }
                display["BURAN_AUDIO_SOCKET"]?.let { path ->
                    val tmpPath = Paths.get(tmp).normalize()
                    val socket = Paths.get(path).normalize()
                    require(socket.startsWith(tmpPath) && socket != tmpPath)
                    env["BURAN_AUDIO_SOCKET"] = "/tmp/${tmpPath.relativize(socket)}"
                    bind("${boxRoot.resolve("etc/passwd").path}:/etc/passwd")
                    bind("${boxRoot.resolve("etc/group").path}:/etc/group")
                }
            }
            env["VK_DRIVER_FILES"] = "/usr/share/vulkan/icd.d/freedreno_icd.json"
            env["VK_LOADER_LAYERS_DISABLE"] = "~implicit~"
            MangoHudOverlay.configure(root, display, env)
            env["VK_LOADER_DEBUG"] = "error,driver"
            env["DXVK_LOG_LEVEL"] = "info"
            env["DXVK_LOG_PATH"] = GUEST_HOME
        }
        display["DISPLAY"]?.let { env["DISPLAY"] = it }
        display["XAUTHORITY"]?.let { path ->
            val auth = File(path)
            val relative = auth.toPath().normalize().takeIf { it.startsWith(Paths.get(tmp).normalize()) }
            env["XAUTHORITY"] = if (relative != null) {
                "/tmp/${Paths.get(tmp).normalize().relativize(relative)}"
            } else {
                cache.resolve("xauthority").also { auth.copyTo(it, overwrite = true) }.let {
                    bind("${it.path}:/home/buran/Xauthority")
                }
                "/home/buran/Xauthority"
            }
        }
        external?.let { env.putAll(it.environment) }
        external?.steamAppId?.let { id ->
            env["SteamAppId"] = id.toString()
            env["SteamGameId"] = id.toString()
            val name = "libkgslshim.so"
            if (dxvk && context.assets.list("gameplay-ports")?.contains(name) == true) {
                val directory = File(tmp, context.packageName).apply { mkdirs() }
                val pending = File.createTempFile(".kgsl-", ".so", directory)
                try {
                    context.assets.open("gameplay-ports/$name").use { input ->
                        pending.outputStream().use { output -> input.copyTo(output) }
                    }
                    Files.move(pending.toPath(), directory.resolve(name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    pending.delete()
                }
                // The Rust shim authorizes the package component of its mapped library path.
                env["BURAN_KGSL_PRELOAD"] = "/tmp/${context.packageName}/$name"
            }
        }
        if (desktopExit != null) {
            val tmpPath = File(tmp).canonicalFile.toPath()
            val marker = desktopExit.canonicalFile.toPath()
            require(marker.startsWith(tmpPath))
            env["BURAN_DESKTOP_EXIT"] = "Z:" + "/tmp/${tmpPath.relativize(marker)}".replace('/', '\\')
        }
        command.addAll(if (dxvk) listOf("/usr/bin/env", "-i") else listOf("/bin/busybox", "env", "-i"))
        command.addAll(env.map { (key, value) -> "$key=$value" })
        command.addAll(listOf("/bin/sh", "/usr/local/bin/buran-box-session",
            "/usr/local/bin/${box.lowercase()}", "$wine/bin/$wineServer", "$wine/bin/$wineLoader"))
        command.addAll(wineCommand)
        val launch = if (pidFile == null) command else listOf(
            "/system/bin/sh", "-c",
            "printf '%s\\n' \"\$\$\" > \"\$1\" || exit 1; shift; exec \"\$@\"",
            "buran-box", pidFile.path,
        ) + command
        val process = try {
            ProcessBuilder(launch).apply {
                environment().remove("LD_PRELOAD")
                environment()["LD_LIBRARY_PATH"] = libs.path
                environment()["PROOT_LOADER"] = loader.path
                if (bits == 32) environment()["PROOT_LOADER_32"] = loader32.path
                environment()["PROOT_TMP_DIR"] = cache.path
                redirectOutput(stdout)
                redirectError(stderr)
            }.start()
        } catch (error: Exception) {
            pidFile?.delete()
            throw error
        }
        if (pidFile != null) gameProcesses[process] = GameProcess(pidFile, proot, pauseFile)
        return process
    }

    @Synchronized
    private fun install(context: Context, bits: Int): File {
        val parent = context.buranFilesDir.resolve("box-runtime").apply { mkdirs() }
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val root = parent.resolve("rootfs")
        if (root.resolve(".version").takeIf(File::isFile)?.readText()?.trim() != stamp ||
            !root.resolve("bin/busybox").canExecute()
        ) {
            NativeRuntimeInstaller.install(context, "box", "box/rootfs.tar.zst", root, stamp,
                myUid().toString(), Os.getgid().toString())
        }
        val arch = if (bits == 32) "86" else "64"
        val wine = root.resolve("opt/buran/wine$arch")
        val loader = if (bits == 32) "wine" else "wine64"
        if (wine.resolve(".version").takeIf(File::isFile)?.readText()?.trim() != stamp ||
            !wine.resolve("bin/$loader").canExecute()
        ) {
            NativeRuntimeInstaller.install(context, "wine$arch", "box/wine$arch.tar.xz", wine, stamp)
        }
        return root
    }

    private fun Sequence<String>.takeLastLines(): String = toList().takeLast(8).joinToString("\n")
}
