package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.SteamBootstrap
import app.gamenative.service.SteamService
import app.gamenative.utils.launchdependencies.BionicSteamAssetsDependency
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.xenvironment.ImageFs
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.stream.Collectors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import timber.log.Timber

internal object BuranSteamClientHost {
    const val MASTER = "127.0.0.1:57343"
    const val SERVICE = "127.0.0.1:57344"
    private const val LIBRARIES = "vendor-steam/android"
    private val libraryNames = listOf(
        "libsteamclient.so", "libsteamnetworkingsockets.so", "libtier0_s.so", "libvstdlib_s.so", "steamservice.so",
    )
    private const val PROTON_ID = BuranInstalledProton.STEAM_CLIENT_PROTON
    private val bridges = listOf(
        "aarch64-unix/lsteamclient.so", "aarch64-windows/lsteamclient.dll", "i386-windows/lsteamclient.dll",
    )
    private val activePrefixes = ConcurrentHashMap.newKeySet<String>()

    private class PreparedGuest(val bundle: Bundle, val projectionAlias: Pair<File, File>?)

    private data class SteamProton(val version: String, val wine: File, val helper: File?)

    private fun selectProton(context: Context, container: Container?): SteamProton {
        val configured = BuranInstalledProton.configuredVersion(container)
        val major = configured?.substringAfter("proton-")?.substringBefore('.')?.toIntOrNull()
        if (configured != null && major != null) {
            val pattern = Regex("proton-$major\\.[0-9.]+(?:-[0-9]+)?-arm64ec(?:-[0-9]+)?")
            val shared = context.filesDir.resolve("imagefs_shared/proton").list().orEmpty().toList()
            val contents = ContentsManager(context).also { it.syncContents() }
            val profiles = contents.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON).orEmpty()
                .map { "proton-" + it.verName.lowercase(Locale.ROOT).removePrefix("proton-") }
            val alternatives = (shared + profiles).filter(pattern::matches).distinct().sortedByDescending {
                Regex("[0-9]+").findAll(it).joinToString(".") { number -> number.value.padStart(8, '0') }
            }
            for (version in (listOf(configured) + alternatives).distinct()) {
                val wine = BuranInstalledProton.resolve(context, version) ?: continue
                val library = wine.resolve("lib/wine")
                val helper = library.resolve("i386-windows/steam.exe")
                if (hasInstalledSteamKit(wine, library, helper)) {
                    Timber.i("Buran Steam client uses installed Proton %s (configured %s)", version, configured)
                    return SteamProton(version, wine, helper)
                }
            }
        }
        val wine = BuranInstalledProton.resolve(context, PROTON_ID)
            ?: error(context.getString(R.string.buran_steam_client_unavailable))
        Timber.i("Buran Steam client uses Proton %s compatibility fallback", PROTON_ID)
        return SteamProton(PROTON_ID, wine, null)
    }

    private fun hasInstalledSteamKit(wine: File, library: File, helper: File): Boolean = runCatching {
        val parts = bridges.map { library.resolve(it) } + helper
        if (!parts.all { it.isFile && it.canRead() && it.canonicalFile.toPath().startsWith(wine.toPath()) }) return false
        val unix = RandomAccessFile(parts[0], "r").use {
            val elf = ByteArray(20).also(it::readFully)
            elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() && elf[2] == 'L'.code.toByte() &&
                elf[3] == 'F'.code.toByte() && elf[4] == 2.toByte() && elf[5] == 1.toByte() &&
                elf[18] == 183.toByte() && elf[19] == 0.toByte()
        }
        val windows = parts.drop(1).map { it.inputStream().use(WindowsExecutableInspector::inspect).kind }
        unix && windows == listOf(ExecutableKind.WINDOWS_64_PE, ExecutableKind.WINDOWS_32_PE, ExecutableKind.WINDOWS_32_PE)
    }.getOrDefault(false)

    /** Runs on the I/O dispatcher, after the guest bridge and Steam account are ready. */
    suspend fun start(context: Context, intent: Intent, container: Container?): Closeable {
        val steamId = SteamService.userSteamId
        val accountId = steamId?.convertToUInt64()
        val accountName = PrefManager.username
        check(SteamService.isConnected && accountId != null && accountId != 0L &&
            accountId == PrefManager.steamUserSteamId64 && !accountName.isNullOrBlank()
        ) { context.getString(R.string.buran_steam_client_sign_in_required) }
        val auth = BuranSteamAuth.authorize(context, accountId, accountName)
        check(SteamService.isConnected && SteamService.userSteamId?.convertToUInt64() == accountId) {
            context.getString(R.string.buran_steam_client_sign_in_required)
        }

        val steamAppId = intent.getIntExtra("steamAppId", 0)
        val account = checkNotNull(steamId).accountID
        check(account in 1L..0xffffffffL && steamAppId > 0)
        val titleKey = "$account/$steamAppId"
        val proton = selectProton(context, container)
        val prefixKey = "$titleKey/${proton.version}"
        val workspace = Files.createTempDirectory(context.cacheDir.toPath(), "buran-steam-").toFile().canonicalFile
        var host: Closeable? = null
        var prepared: PreparedGuest? = null
        var acquiredPrefix = false
        var handedOff = false
        fun cleanupGuest() {
            try {
                prepared?.projectionAlias?.let { (link, projection) ->
                    BuranPrivatePrefixFiles.removeManagedLink(link, projection)
                }
            } finally {
                try {
                    deleteWorkspace(workspace)
                } finally {
                    if (acquiredPrefix) activePrefixes.remove(titleKey)
                }
            }
        }
        try {
            check(activePrefixes.add(titleKey)) { context.getString(R.string.second_screen_buran_session_active) }
            acquiredPrefix = true
            val imageFs = ImageFs.find(context)
            val available = context.assets.list(LIBRARIES).orEmpty().toSet()
            val libDir = if (available.isNotEmpty()) {
                check(libraryNames.all(available::contains)) { context.getString(R.string.buran_steam_client_unavailable) }
                val staging = workspace.resolve("android").apply { mkdir() }
                libraryNames.forEach { name ->
                    context.assets.open("$LIBRARIES/$name").use { input ->
                        File(staging, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                staging
            } else {
                imageFs.libDir
            }
            val lib = File(libDir, "libsteamclient.so").takeIf { it.isFile && it.canRead() }
                ?: error(context.getString(R.string.buran_steam_client_unavailable))
            val home = File(context.filesDir, "buran-steam-host/$accountId/${auth.generation}")
            val steamRoot = File(home, "Steam")
            val breakpad = File(home, "breakpad")
            check(listOf(File(steamRoot, "config"), breakpad).all { it.isDirectory || it.mkdirs() }) {
                context.getString(R.string.buran_steam_client_unavailable)
            }
            val sharedRemote = checkNotNull(intent.getStringExtra("steamRemoteDirectory")) {
                context.getString(R.string.buran_steam_save_location_changed)
            }
            BuranPrivatePrefixFiles.linkSteamHostUserdata(steamRoot, account, steamAppId, File(sharedRemote))
            val preparedGuest = prepareGuest(context, workspace, intent, container, libDir, account, prefixKey, proton)
            prepared = preparedGuest
            val guest = preparedGuest.bundle
            host = SteamBootstrap.startBuran(
                context, steamAppId, lib.path, home.path, MASTER, SERVICE,
                mapOf(
                    "LD_LIBRARY_PATH" to listOf(libDir, imageFs.libDir).distinct().joinToString(":") { it.path },
                    "_STEAM_SETENV_MANAGER" to "1",
                    "STEAM_BASE_FOLDER" to steamRoot.path,
                    "BREAKPAD_DUMP_LOCATION" to breakpad.path,
                    "ENABLE_VK_LAYER_VALVE_steam_overlay_1" to "0",
                    "STEAMVIDEOTOKEN" to "1",
                    "SteamOS" to "1",
                    "SteamUser" to accountName,
                ),
                accountName, auth.refreshToken, accountId,
            ) ?: error(context.getString(R.string.buran_steam_client_unavailable))
            check(SteamService.isConnected && SteamService.userSteamId?.convertToUInt64() == accountId) {
                context.getString(R.string.buran_steam_client_sign_in_required)
            }
            guest.putStringArrayList("environment", arrayListOf(
                "Steam3Master=$MASTER", "SteamClientService=$SERVICE", "SteamUser=$accountName",
                "SteamAppUser=$accountName", "STEAMID=$accountId", "SteamOS=1", "STEAMVIDEOTOKEN=1",
                "SteamClientLaunch=1", "SteamEnv=1",
            ))
            guest.putString("steamHostHome", home.path)
            intent.putExtra("steamBionicRuntime", guest)
            val session = checkNotNull(host)
            val closed = AtomicBoolean(false)
            handedOff = true
            return Closeable {
                if (closed.compareAndSet(false, true)) {
                    try {
                        session.close()
                    } finally {
                        cleanupGuest()
                    }
                }
            }
        } catch (error: SteamBootstrap.BuranLoginFailure) {
            if (error.result in setOf(5, 15, 26)) {
                BuranSteamAuth.forgetRejected(context, accountId)
                error(context.getString(R.string.buran_steam_auth_rejected))
            }
            throw error
        } finally {
            if (!handedOff) {
                try {
                    host?.close()
                } finally {
                    cleanupGuest()
                }
            }
        }
    }

    @Synchronized
    private fun prepareGuest(
        context: Context,
        workspace: File,
        intent: Intent,
        container: Container?,
        libDir: File,
        accountId: Long,
        prefixKey: String,
        proton: SteamProton,
    ): PreparedGuest {
        val imageFs = ImageFs.find(context)
        val wine = proton.wine
        val wineRoot = workspace.resolve(proton.version)
        copyTree(wine, wineRoot, useHardLinks = true)

        val bridgePrefix = "vendor-steam/proton10"
        val packagedBridge = context.assets.list("$bridgePrefix/aarch64-unix").orEmpty().contains("lsteamclient.so")
        val guestLib = wineRoot.resolve("lib/wine")
        check(guestLib.canonicalFile.toPath().startsWith(wineRoot.toPath())) {
            context.getString(R.string.buran_steam_client_unavailable)
        }
        if (proton.helper == null && packagedBridge) {
            for (name in bridges) {
                val target = guestLib.resolve(name)
                check(target.parentFile.canonicalFile.toPath().startsWith(wineRoot.toPath()))
                target.parentFile?.mkdirs()
                Files.deleteIfExists(target.toPath())
                context.assets.open("$bridgePrefix/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } else if (proton.helper == null) {
            val archive = imageFs.filesDir.resolve("lsteamclient-arm64ec-proton10.tzst")
            if (archive.isFile) {
                val extracted = workspace.resolve("bridge").apply { mkdir() }
                check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, archive, extracted)) {
                    context.getString(R.string.buran_steam_client_unavailable)
                }
                for (name in bridges) {
                    val target = guestLib.resolve(name)
                    check(target.parentFile.canonicalFile.toPath().startsWith(wineRoot.toPath()))
                    target.parentFile?.mkdirs()
                    Files.copy(extracted.resolve(name).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        check(bridges.all {
            val bridge = guestLib.resolve(it)
            bridge.canonicalFile.toPath().startsWith(wineRoot.toPath()) && bridge.length() > 0
        }) {
            context.getString(R.string.buran_steam_client_unavailable)
        }
        val unixBridge = guestLib.resolve(bridges.first())
        val detached = Files.createTempFile(unixBridge.parentFile.toPath(), ".buran-lsteamclient-", ".so")
        try {
            Files.copy(unixBridge.toPath(), detached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(detached, unixBridge.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(detached)
        }
        BionicSteamAssetsDependency.relocateSteamClientLookup(unixBridge)

        val appId = intent.getIntExtra("steamAppId", 0)
        check(accountId in 1L..0xffffffffL && appId > 0)
        intent.getStringExtra("steamRemoteDirectory")?.let { path ->
            val remote = File(path).canonicalFile
            check(remote.name == "remote" && remote.parentFile?.name == appId.toString() &&
                remote.parentFile?.parentFile?.name == accountId.toString())
        }
        val owned = context.filesDir.canonicalFile.resolve("buran-steam-prefixes/$prefixKey")
        check(owned == owned.canonicalFile)
        val prefix = owned.resolve(".wine")
        BuranInstalledProton.preparePrefix(context, wine, proton.version, prefix, intent)
        BuranInstalledProton.linkSteamSaves(prefix, intent)
        val drive = prefix.resolve("drive_c")
        val windows = drive.resolve("windows")
        fun privateDirectory(dir: File): File {
            check(dir.canonicalFile.toPath().startsWith(prefix.toPath()))
            check(dir.isDirectory || dir.mkdirs())
            return dir
        }
        val system32 = privateDirectory(windows.resolve("system32"))
        val syswow64 = privateDirectory(windows.resolve("syswow64"))
        for ((arch, destination) in listOf("aarch64-windows" to system32, "i386-windows" to syswow64)) {
            replaceRuntimeFile(guestLib.resolve("$arch/lsteamclient.dll"), destination.resolve("lsteamclient.dll"))
        }
        val fex = BuranInstalledProton.fexSystem32(context, container)
            ?: error(context.getString(R.string.buran_steam_client_unavailable))
        val steam = privateDirectory(drive.resolve("Program Files (x86)/Steam"))
        val helper = proton.helper ?: imageFs.filesDir.resolve("steam.exe")
        val dlls = imageFs.filesDir.resolve("steamclient-dlls-20260619.tzst")
        check(helper.isFile && dlls.isFile) { context.getString(R.string.buran_steam_client_unavailable) }
        replaceRuntimeFile(helper, steam.resolve("steam.exe"))
        val extracted = workspace.resolve("steam-client").apply { mkdir() }
        check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, dlls, extracted)) {
            context.getString(R.string.buran_steam_client_unavailable)
        }
        for (name in listOf("steamclient.dll", "steamclient64.dll")) {
            val dll = extracted.walkTopDown().maxDepth(12).firstOrNull { it.isFile && it.name.equals(name, true) }
                ?: error(context.getString(R.string.buran_steam_client_unavailable))
            replaceRuntimeFile(dll, steam.resolve(name))
        }
        configureBuranWineDesktop(prefix, intent.getBooleanExtra("wineDesktop", true))
        val userRegFile = prefix.resolve("user.reg")
        if (userRegFile.isFile && !Files.isSymbolicLink(userRegFile.toPath())) {
            WineRegistryEditor(userRegFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                val key = "Software\\Valve\\Steam\\ActiveProcess"
                val steamPath = "C:\\Program Files (x86)\\Steam"
                editor.setDwordValue(key, "ActiveUser", accountId.toInt())
                editor.setStringValue(key, "SteamClientDll", "$steamPath\\steamclient.dll")
                editor.setStringValue(key, "SteamClientDll64", "$steamPath\\steamclient64.dll")
                editor.setStringValue(key, "Universe", "Public")
            }
        }

        val source = container?.rootDir?.resolve(".wine/drive_c")
        BuranPhysXDependencies.reuse(container, prefix)
        if (source != null) {
            // Keep the title's installed x86 VC80 SxS assemblies and manifests with their original names.
            val assemblies = source.resolve("windows/winsxs")
            val privateAssemblies = privateDirectory(windows.resolve("winsxs"))
            val crt = Regex("x86_microsoft\\.vc80\\.crt_.*", RegexOption.IGNORE_CASE)
            fun copyAssemblyFiles(from: File, to: File) {
                from.listFiles().orEmpty().filter { crt.matches(it.name) }.forEach { assembly ->
                    val target = to.resolve(assembly.name)
                    if (target.exists() || Files.isSymbolicLink(target.toPath())) return@forEach
                    if (assembly.isDirectory) copyTree(assembly.canonicalFile, target, useHardLinks = false)
                    else if (assembly.isFile) Files.copy(assembly.toPath(), target.toPath())
                }
            }
            copyAssemblyFiles(assemblies, privateAssemblies)
            copyAssemblyFiles(assemblies.resolve("manifests"), privateDirectory(privateAssemblies.resolve("manifests")))
        }
        val game = File(checkNotNull(intent.getStringExtra("gameDirectory"))).canonicalFile
        val gameProjection = projectGame(workspace, game)
        val common = privateDirectory(steam.resolve("steamapps/common"))
        val steamGame = common.resolve(game.name)
        val bundle = Bundle().apply {
            putString("wineRoot", wineRoot.path)
            putString("prefix", prefix.path)
            putString("fexSystem32", fex.path)
            putStringArrayList("fexEnvironment", BuranCpuPackages.fexEnvironment(context, container))
            BuranInstalledProton.wowBox64(context, container)?.let { putString("wowBox64", it.path) }
            intent.getBundleExtra("buranGraphics")?.let { putBundle("buranGraphics", it) }
            putString("clientLibDirectory", libDir.path)
            imageFs.libDir.resolve(BuildConfig.PRELOAD_BIONIC_SO).takeIf { it.isFile }?.let {
                putString("wineWxLibrary", it.path)
            }
            putLong("steamAccountId", accountId)
            gameProjection?.let { putString("gameProjection", it.path) }
        }
        BuranPrivatePrefixFiles.replaceManagedLink(steamGame, gameProjection ?: game)
        return PreparedGuest(bundle, gameProjection?.let { steamGame to it })
    }

    private fun replaceRuntimeFile(source: File, destination: File) {
        if (destination.isFile && !Files.isSymbolicLink(destination.toPath()) &&
            org.apache.commons.io.FileUtils.contentEquals(source, destination)
        ) return
        val temporary = Files.createTempFile(destination.parentFile.toPath(), ".buran-steam-", ".tmp")
        try {
            Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING)
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun projectGame(workspace: File, root: File): File? {
        val rootPath = root.canonicalFile.toPath()
        val backups = Files.walk(rootPath).use { paths ->
            paths.filter {
                Files.isRegularFile(it, NOFOLLOW_LINKS) &&
                    it.fileName.toString().lowercase(Locale.ROOT) in setOf("steam_api.dll.orig", "steam_api64.dll.orig")
            }.filter { original ->
                val live = original.resolveSibling(original.fileName.toString().dropLast(".orig".length))
                !org.apache.commons.io.FileUtils.contentEquals(original.toFile(), live.toFile())
            }.collect(Collectors.toList())
        }
        if (backups.isEmpty()) return null
        val output = workspace.resolve("game")
        val replacements = backups.groupBy { it.parent }.mapValues { (_, files) ->
            files.associateBy { it.fileName.toString().dropLast(".orig".length).lowercase(Locale.ROOT) }
        }
        fun mirror(from: File, to: File) {
            check(to.mkdir())
            val siblings = checkNotNull(from.listFiles())
            val overrides = replacements[from.toPath()].orEmpty()
            for (entry in siblings) {
                val target = to.resolve(entry.name).toPath()
                val source = overrides[entry.name.lowercase(Locale.ROOT)] ?: entry.toPath()
                if (source == entry.toPath() && backups.any { it.startsWith(entry.toPath()) && it != entry.toPath() }) {
                    mirror(entry, target.toFile())
                } else {
                    Files.createSymbolicLink(target, source)
                }
            }
            for ((name, backup) in overrides) {
                if (siblings.none { it.name.equals(name, true) }) {
                    Files.createSymbolicLink(to.resolve(name).toPath(), backup)
                }
            }
        }
        mirror(rootPath.toFile(), output)
        return output
    }

    private fun copyTree(source: File, destination: File, useHardLinks: Boolean) {
        val sourcePath = source.toPath()
        Files.walk(sourcePath).use { paths ->
            paths.forEach { path ->
                val target = destination.toPath().resolve(sourcePath.relativize(path))
                when {
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> Files.createDirectories(target)
                    Files.isSymbolicLink(path) -> Files.createSymbolicLink(target, Files.readSymbolicLink(path))
                    useHardLinks -> Files.createLink(target, path)
                    else -> Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun deleteWorkspace(file: File) {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
