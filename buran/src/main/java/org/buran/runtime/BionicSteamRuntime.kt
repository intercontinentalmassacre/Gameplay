package org.buran.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.system.Os
import android.util.Log
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.function.BooleanSupplier

/** Runs Gameplay's installed Android ARM64EC Proton under Buran's Xwayland. */
internal class BionicSteamRuntime private constructor(
    private val image: File,
    private val wine: File,
    private val prefix: File,
    private val fexSystem32: File,
    private val steamLibs: File?,
    private val gameProjection: File?,
    private val steamAccountId: Long,
    private val home: File,
    private val wowBox64: File?,
    private val graphics: Bundle?,
    private val wineWxLibrary: File?,
    private val relocationLibrary: File?,
    private val dri3Bridge: File?,
    private val environment: Map<String, String>,
    private val fexEnvironment: Map<String, String>,
) {
    private var sessionEnv: Map<String, String>? = null

    fun start(
        context: Context, request: ExternalWindowsLaunch, display: Map<String, String>, args: List<String>,
        dependencies: SteamInstallDependencies? = null,
        onDependencyInstalling: () -> Unit = {},
        isCancelled: () -> Boolean = { false },
        onShaderPrewarm: () -> Unit = {},
    ): Process {
        val appId = request.steamAppId
        require(steamLibs == null || appId != null) { "Для клиента Steam нужен Steam App ID" }
        val common = prefix.resolve("drive_c/Program Files (x86)/Steam/steamapps/common/${request.directory.name}")
        val gameRoot = gameProjection ?: request.directory
        val executable = if (common.canonicalFile == gameRoot) {
            "C:\\Program Files (x86)\\Steam\\steamapps\\common\\${request.directory.name}\\${request.game.exe.replace('/', '\\')}"
        } else if (gameProjection != null) {
            val devices = prefix.resolve("dosdevices").apply { mkdirs() }
            val drive = File(devices, "d:")
            val temporary = devices.resolve(".buran-game-d")
            try {
                temporary.delete()
                Os.symlink(gameProjection.path, temporary.path)
                Os.rename(temporary.path, drive.path)
            } finally {
                temporary.delete()
            }
            "D:\\" + request.game.exe.replace('/', '\\')
        } else {
            "Z:" + request.directory.resolve(request.game.exe).path.replace('/', '\\')
        }
        val cwd = gameProjection?.resolve(request.relativeWorkingDirectory) ?: request.workingDirectory
        require(cwd.isDirectory && (gameProjection == null || gameProjection.resolve(request.game.exe).isFile)) {
            "Рабочая копия игры Steam недоступна"
        }
        val tmp = checkNotNull(display["TMPDIR"])
        val session = environment + mapOf(
            "DISPLAY" to checkNotNull(display["DISPLAY"]),
            "XAUTHORITY" to checkNotNull(display["XAUTHORITY"]),
            "TMPDIR" to tmp,
        ) + (if (appId == null) emptyMap() else mapOf("SteamAppId" to "$appId", "SteamGameId" to "$appId")) +
            (if (steamLibs == null) TurnipDrivers.bionicEnvironment(context, graphics, systemFallback = true) else emptyMap()) +
            gamepadEnvironment(context, display)
        sessionEnv = session
        val system = prefix.resolve("drive_c/windows/system32")
        Files.createDirectories(system.toPath())
        val cpuFiles = listOf("libwow64fex.dll", "libarm64ecfex.dll").associateWith { fexSystem32.resolve(it) } +
            wowBox64?.let { mapOf("wowbox64.dll" to it) }.orEmpty()
        for ((name, source) in cpuFiles) {
            val target = system.resolve(name)
            if (source.canonicalFile == target.canonicalFile) continue
            val pending = File.createTempFile(".buran-cpu-", ".dll", system)
            try {
                source.copyTo(pending, overwrite = true)
                Files.move(pending.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                pending.delete()
            }
        }
        val loader = wine.resolve("bin/wine")
        val logName = if (steamLibs == null) "bionic-proton" else "bionic-steam"
        val stdout = context.buranFilesDir.resolve("$logName-stdout.txt")
        val stderr = context.buranFilesDir.resolve("$logName-stderr.txt")
        stdout.writeText("")
        stderr.writeText("")
        fun command(directory: File, arguments: List<String>, env: Map<String, String> = session) =
            launch(loader, directory, arguments, env)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr)).start()
        // Prefix maintenance never needs to download Wine Mono or Gecko.
        val setupOverrides = session["WINEDLLOVERRIDES"] ?: "winemenubuilder.exe=d"
        val maintenance = session + ("WINEDLLOVERRIDES" to "$setupOverrides;mscoree,mshtml=d")
        fun initialize(arguments: List<String>) {
            val process = command(prefix, arguments, maintenance)
            try {
                check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    wineFailure("Proton", if (process.isAlive) -1 else process.exitValue(), stderr)
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        try {
            if (!prefix.resolve("system.reg").isFile || !prefix.resolve("user.reg").isFile) {
                initialize(listOf("wineboot", "-u"))
            }
            if ("PULSE_SERVER" in session) {
                initialize(listOf("reg", "add", "HKCU\\Software\\Wine\\Drivers",
                    "/v", "Audio", "/t", "REG_SZ", "/d", "pulse", "/f"))
            }
            dependencies?.runBionic(
                gameRoot, context.buranFilesDir.resolve("steam-dependency-status.txt"), onDependencyInstalling,
                verifiedInstalled = { installer -> LegacyVc80Runtime.isInstalled(prefix, installer) },
            ) {
                    directory, command, output, timeout ->
                val process = launch(loader, directory, command, if (command.firstOrNull() == "reg") maintenance else session)
                    .redirectOutput(output).redirectError(ProcessBuilder.Redirect.appendTo(stderr)).start()
                try {
                    if (process.waitFor(timeout, TimeUnit.SECONDS)) process.exitValue() else -1
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
            if (steamLibs != null) {
                val registry = File.createTempFile("buran-steam-", ".reg", prefix)
                try {
                    val steam = """C:\\Program Files (x86)\\Steam"""
                    registry.writeText("""
                        REGEDIT4

                        [HKEY_CURRENT_USER\Software\Valve\Steam]
                        "SteamExe"="$steam\\steam.exe"
                        "SteamPath"="$steam"

                        [HKEY_CURRENT_USER\Software\Valve\Steam\ActiveProcess]
                        "ActiveUser"=dword:${steamAccountId.toString(16).padStart(8, '0')}
                        "SteamClientDll"="$steam\\steamclient.dll"
                        "SteamClientDll64"="$steam\\steamclient64.dll"
                        "Universe"="Public"

                    """.trimIndent())
                    initialize(listOf("regedit", "/S", "Z:" + registry.path.replace('/', '\\')))
                } finally {
                    registry.delete()
                }
            }
            val dxvk = DxvkComponents.resolve(context, graphics)
            DxvkComponents.install(prefix, dxvk, request.game.bits)
            val overrides = session["WINEDLLOVERRIDES"] ?: "winemenubuilder.exe=d"
            val dxvkFiles = dxvk?.files(request.game.bits).orEmpty()
            val dxvkOverrides = DxvkComponents.overrides(dxvkFiles.keys)
            val dri3Trace = if (dri3Bridge != null &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                context.buranFilesDir.resolve("dri3-bridge-trace").isFile
            ) mapOf("BURAN_DRI3_BRIDGE" to "trace") else emptyMap()
            val driverEnvironment = TurnipDrivers.bionicEnvironment(context, graphics, systemFallback = true,
                hasNativeDxvk = dxvkFiles.isNotEmpty())
            val dxvkEnvironment = if (dxvkFiles.isEmpty()) emptyMap() else {
                val settings = graphics?.getBundle("dxvkEnvironment")
                settings?.keySet()?.mapNotNull { name ->
                    settings.getString(name)?.let { name to it }
                }.orEmpty().toMap() + BionicShaderCaches.dxvkStateCache(
                    context.buranFilesDir, request.game.id, graphics?.getString("dxvkVersion").orEmpty(), request.game.bits,
                )
            }
            val gameEnv = session + ("WINEDLLOVERRIDES" to "$overrides;$dxvkOverrides") + dri3Trace +
                driverEnvironment + fexEnvironment + dxvkEnvironment +
                BionicShaderCaches.environment(context, request.game, image, driverEnvironment) +
                if (wineWxLibrary != null) mapOf("WINEMU_HOST_PKG" to context.packageName) else emptyMap()
            val game = launch(loader, cwd, listOf(executable) + args, gameEnv, dri3Bridge?.path)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(stdout))
                .redirectError(ProcessBuilder.Redirect.appendTo(stderr))
            val cancelled = BooleanSupplier { isCancelled() || Thread.currentThread().isInterrupted }
            if (cancelled.asBoolean) throw InterruptedException()
            if (graphics?.getBoolean("fossilizeEnabled") == true) {
                prepareFossilize(context, request.key, game.environment(), cancelled, Runnable(onShaderPrewarm))
            }
            if (cancelled.asBoolean) throw InterruptedException()
            return game.start()
        } catch (error: Exception) {
            val interrupted = Thread.interrupted()
            try {
                runCatching { stopServer(session) }.exceptionOrNull()?.let(error::addSuppressed)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            throw error
        }
    }

    private fun prepareFossilize(
        context: Context,
        installKey: String,
        environment: MutableMap<String, String>,
        cancelled: BooleanSupplier,
        onReplay: Runnable,
    ) {
        val bridge = try {
            Class.forName("app.gamenative.shadercache.BuranFossilizeBridge")
        } catch (_: ClassNotFoundException) {
            return // The standalone Buran APK has no Gameplay shader cache manager.
        }
        try {
            val method = bridge.getMethod("prepare", Context::class.java, String::class.java,
                Map::class.java, BooleanSupplier::class.java, Runnable::class.java)
            @Suppress("UNCHECKED_CAST")
            val capture = method.invoke(null, context, installKey, environment.toMap(), cancelled, onReplay)
                as? Map<String, String> ?: return
            if (capture.isNotEmpty() && !cancelled.asBoolean) {
                environment.remove("DISABLE_FOSSILIZE")
                environment.remove("FOSSILIZE_DUMP_PATH_READ_ONLY")
                environment.putAll(capture)
            }
        } catch (error: InvocationTargetException) {
            val cause = error.targetException
            if (cause is InterruptedException) throw cause
            Log.w("BuranShaderCache", "Fossilize preparation failed", cause)
        } catch (error: ReflectiveOperationException) {
            Log.w("BuranShaderCache", "Fossilize bridge unavailable", error)
        }
    }

    fun stop(guest: Process) {
        if (guest.isAlive) guest.destroy()
        stopServer(checkNotNull(sessionEnv))
        if (guest.isAlive) guest.destroyForcibly()
    }

    private fun gamepadEnvironment(context: Context, display: Map<String, String>): Map<String, String> {
        val base = display["EVSHIM_BASE_PATH"]?.let { File(it).canonicalFile } ?: return emptyMap()
        val session = File(checkNotNull(display["XAUTHORITY"])).canonicalFile.parentFile
        val memory = base.resolve("gamepad_shm/gamepad.mem")
        val library = File(context.applicationInfo.nativeLibraryDir, "libevshim.so")
        check(base.parentFile == session && memory.isFile && memory.length() == PhysicalGamepad.SHARED_SIZE &&
            library.isFile) { "Не удалось подключить геймпад к Proton" }
        // Wine starts winebus through winedevice during the first prefix command.
        return mapOf(
            "EVSHIM_BASE_PATH" to base.path,
            "EVSHIM_WINE" to "1",
            "EVSHIM_MAX_PLAYERS" to "1",
            "LD_PRELOAD" to listOfNotNull(environment["LD_PRELOAD"], library.path).joinToString(":"),
        )
    }

    private fun stopServer(env: Map<String, String>) {
        val stop = launch(wine.resolve("bin/wineserver"), prefix, listOf("-k"), env)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        if (!stop.waitFor(3, TimeUnit.SECONDS)) stop.destroyForcibly()
        val wait = launch(wine.resolve("bin/wineserver"), prefix, listOf("-w"), env)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        if (!wait.waitFor(5, TimeUnit.SECONDS)) {
            wait.destroyForcibly()
            error("Сервер Proton не завершился")
        }
        check(wait.exitValue() == 0) { "Сервер Proton не завершился" }
    }

    private fun launch(exe: File, cwd: File, args: List<String>, env: Map<String, String>, renderer: String? = null) =
        ProcessBuilder(listOf(exe.path) + args).directory(cwd).apply {
            environment().clear()
            val library = image.resolve("usr/lib")
            val steam = prefix.resolve("drive_c/Program Files (x86)/Steam")
            environment().putAll(mapOf(
                "ANDROID_ROOT" to "/system",
                "HOME" to home.path,
                "USER" to "xuser",
                "LANG" to "C.UTF-8",
                "PATH" to "${wine.resolve("bin").path}:${image.resolve("usr/bin").path}:/system/bin",
                "LD_LIBRARY_PATH" to listOfNotNull(steamLibs, library, wine.resolve("lib"),
                    wine.resolve("lib/wine/aarch64-unix"), File("/system/lib64")).joinToString(":") { it.path },
                "TMPDIR" to image.resolve("usr/tmp").path,
                "REDIRECT_EXEC__PROC_SELF_EXE" to wine.resolve("bin/wine").path,
                "PREFIX" to image.resolve("usr").path,
                "WINEPREFIX" to prefix.path,
                "WINESERVER" to wine.resolve("bin/wineserver").path,
                "WINEDLLPATH" to wine.resolve("lib/wine").path,
                "HODLL" to if (wowBox64 == null) "libwow64fex.dll" else "wowbox64.dll",
                "WINEESYNC" to "0",
                "WINEFSYNC" to "0",
                "PROTON_NO_NTSYNC" to "1",
                "WINEDEBUG" to "-all,err+all",
                "WINEDLLOVERRIDES" to "winemenubuilder.exe=d",
                "FONTCONFIG_PATH" to image.resolve("usr/etc/fonts").path,
            ))
            if (steamLibs != null) environment().putAll(mapOf(
                "STEAM_BASE_FOLDER" to home.resolve("Steam").path,
                "BREAKPAD_DUMP_LOCATION" to home.resolve("breakpad").path,
                "WINESTEAMCLIENTPATH" to steam.resolve("linux32/steamclient.so").path,
                "WINESTEAMCLIENTPATH64" to steam.resolve("linux64/steamclient.so").path,
                "SteamPath" to "C:\\Program Files (x86)\\Steam",
                "ValvePlatformMutex" to "c:\\Program Files (x86)\\Steam/",
                "SteamClientLaunch" to "1",
                "SteamEnv" to "1",
                "_STEAM_SETENV_MANAGER" to "1",
                "Steam3Master" to "127.0.0.1:57343",
                "SteamClientService" to "127.0.0.1:57344",
            ))
            environment().putAll(env)
            val preloads = guestPreloads(environment()["LD_PRELOAD"], wineWxLibrary?.path, relocationLibrary?.path, renderer)
            if (preloads.isEmpty()) environment().remove("LD_PRELOAD")
            else environment()["LD_PRELOAD"] = preloads
            if (relocationLibrary != null) environment()["BURAN_REDIRECT_IMAGEFS"] = image.path
        }

    companion object {
        const val EXTRA = "steamBionicRuntime"

        internal fun guestPreloads(configured: String?, wineWx: String?, relocation: String? = null, renderer: String? = null): String {
            val libraries = configured.orEmpty().split(':', ' ')
                .filter { it.isNotBlank() && it != wineWx && it != relocation && it != renderer }
            // The compatibility library must see already-relocated file paths.
            return (listOfNotNull(renderer) + libraries + listOfNotNull(relocation, wineWx)).distinct().joinToString(":")
        }

        private val fexOption = Regex("FEX_[A-Z0-9_]+")
        private val runtimeNames = setOf(
            "SteamUser", "SteamAppUser", "STEAMID", "SteamOS", "STEAMVIDEOTOKEN", "ValvePlatformMutex",
            "Steam3Master", "SteamClientService", "SteamClientLaunch", "SteamEnv",
            "BREAKPAD_DUMP_LOCATION", "STEAM_SSL_CERT_FILE", "SSL_CERT_FILE", "SSL_CERT_DIR", "OPENSSL_CONF",
            "LD_PRELOAD", "ANDROID_SYSVSHM_SERVER", "REDIRECT_EXEC__PROC_SELF_EXE", "PULSE_SERVER",
            "FEX_CONFIG_LOCATION", "WINEDLLOVERRIDES", "VK_DRIVER_FILES", "VK_ICD_FILENAMES",
        )

        fun resolve(context: Context, bundle: Bundle): BionicSteamRuntime {
            val files = context.filesDir.canonicalFile
            val cache = context.cacheDir.canonicalFile
            fun directory(key: String): File {
                val path = File(requireNotNull(bundle.getString(key))).canonicalFile
                require(path.isDirectory && (path.toPath().startsWith(files.toPath()) || path.toPath().startsWith(cache.toPath()))) {
                    "Недоступен каталог Proton: $key"
                }
                return path
            }
            val wine = directory("wineRoot")
            val prefix = directory("prefix")
            val fex = directory("fexSystem32")
            val steamClient = bundle.getBoolean("steamClient", true)
            val client = if (steamClient) directory("clientLibDirectory") else null
            val account = bundle.getLong("steamAccountId")
            require(!steamClient || account in 1L..0xffffffffL) { "Недопустимый идентификатор пользователя Steam" }
            val home = if (steamClient) directory("steamHostHome") else checkNotNull(prefix.parentFile)
            val wineWx = bundle.getString("wineWxLibrary")?.let { value ->
                File(value).canonicalFile.also {
                    require(it.isFile && it.parentFile == files.resolve("imagefs/usr/lib").canonicalFile &&
                        it.name in setOf("libredirect-bionic.so", "libredirect-bionic-wx.so"))
                }
            }
            val relocation = File(context.applicationInfo.nativeLibraryDir, "libburan_redirect_bionic.so")
                .takeIf(File::isFile)
            require(wineWx == null || relocation != null) { "В APK нет модуля перенаправления Proton" }
            val dri3Bridge = File(context.applicationInfo.nativeLibraryDir, "libburan_dri3bridge.so").takeIf(File::isFile)
            val projection = bundle.getString("gameProjection")?.let { value ->
                File(value).canonicalFile.also {
                    require(it.isDirectory && it.toPath().startsWith(cache.toPath())) {
                        "Недоступна рабочая копия игры Steam"
                    }
                }
            }
            val image = files.resolve("imagefs")
            val winelib = wine.resolve("lib/wine")
            require(wine.resolve("bin/wine").canExecute() && wine.resolve("bin/wineserver").canExecute() &&
                winelib.resolve("aarch64-windows").isDirectory && winelib.resolve("i386-windows").isDirectory &&
                image.resolve("usr/lib/libxcb.so").isFile
            ) { "Не найден совместимый установленный Proton ARM64EC" }
            require(client == null || (winelib.resolve("aarch64-unix/lsteamclient.so").isFile &&
                winelib.resolve("i386-windows/lsteamclient.dll").isFile &&
                winelib.resolve("aarch64-windows/lsteamclient.dll").isFile &&
                prefix.resolve("drive_c/Program Files (x86)/Steam/steam.exe").isFile &&
                client.resolve("libsteamclient.so").isFile)
            ) { "Не найден установленный Proton ARM64EC с соответствующими библиотеками Steam" }
            require(WcpFiles.validFexDirectory(fex)) { "Отсутствует FEX для Proton x86" }
            val wow = bundle.getString("wowBox64")?.let { value ->
                File(value).canonicalFile.also {
                    require(it.toPath().startsWith(files.toPath()) && WcpFiles.validWowBox64(it)) {
                        "Не найден совместимый установленный WowBox64"
                    }
                }
            }
            val env = bundle.getStringArrayList("environment").orEmpty().associate { assignment ->
                val name = assignment.substringBefore('=')
                require('=' in assignment && '\u0000' !in assignment &&
                    (name in runtimeNames || name.matches(Regex("GNOVERLAY_[A-Z0-9_]+")))) {
                    "Недопустимая переменная среды Proton"
                }
                name to assignment.substringAfter('=')
            }
            val fexEnv = if (wow != null) emptyMap() else
                bundle.getStringArrayList("fexEnvironment").orEmpty().associate { assignment ->
                    val name = assignment.substringBefore('=')
                    require('=' in assignment && fexOption.matches(name) && assignment.none(Char::isISOControl)) {
                        "Недопустимый параметр FEX"
                    }
                    name to assignment.substringAfter('=')
                }
            return BionicSteamRuntime(image, wine, prefix, fex, client, projection, account, home, wow,
                bundle.getBundle("buranGraphics"), wineWx, relocation, dri3Bridge, env, fexEnv)
        }
    }
}
