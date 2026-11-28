package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import app.gamenative.BuildConfig
import app.gamenative.itch.ItchArgCodec
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.FileUtils
import app.gamenative.utils.SteamUtils
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import java.io.File
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Experimental, per-title entry point to the runtime embedded in Gameplay. */
internal object BuranGameLaunch {
    private const val ACTION = "org.buran.runtime.PLAY_GAME"
    private const val ACTIVITY = "org.buran.runtime.GameplayLaunchActivity"
    private const val PREFERENCES = "local_game_runtimes"
    private const val WINE_DESKTOP = "wine-desktop:"
    private const val FRAMEGEN_2X = "framegen-2x:"
    enum class Runtime(val key: String) {
        WINLATOR(""), BUNDLED(""), STEAM_CLIENT("steam-client:"), INSTALLED_PROTON("installed-proton:"),
    }

    fun isAvailable(): Boolean = BuildConfig.MODERN_ANDROID && "arm64-v8a" in Build.SUPPORTED_ABIS

    fun isSelected(context: Context, appId: String): Boolean =
        isAvailable() && context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(appId, false)

    fun selection(context: Context, appId: String): Runtime {
        if (!isSelected(context, appId)) return Runtime.WINLATOR
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return when {
            preferences.getBoolean(Runtime.STEAM_CLIENT.key + appId, false) -> Runtime.STEAM_CLIENT
            preferences.getBoolean(Runtime.INSTALLED_PROTON.key + appId, false) -> Runtime.INSTALLED_PROTON
            else -> Runtime.BUNDLED
        }
    }

    fun observeSelection(context: Context, appId: String): Flow<Runtime> = callbackFlow {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == appId || Runtime.entries.any { it.key + appId == key }) {
                trySend(selection(context, appId))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(selection(context, appId))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun select(context: Context, appId: String, runtime: Runtime) {
        val editor = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
        if (runtime != Runtime.WINLATOR) editor.putBoolean(appId, true) else editor.remove(appId)
        for (option in listOf(Runtime.STEAM_CLIENT, Runtime.INSTALLED_PROTON)) {
            val key = option.key + appId
            if (runtime == option) editor.putBoolean(key, true) else editor.remove(key)
        }
        editor.apply()
    }

    fun wineDesktop(context: Context, appId: String, runtime: Runtime): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(WINE_DESKTOP + appId, runtime == Runtime.STEAM_CLIENT)

    fun setWineDesktop(context: Context, appId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(WINE_DESKTOP + appId, enabled).apply()
    }

    fun framegenEnabled(context: Context, appId: String): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(FRAMEGEN_2X + appId, false)

    fun setFramegenEnabled(context: Context, appId: String, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(FRAMEGEN_2X + appId, enabled).apply()
    }

    fun playIntent(
        context: Context,
        appId: String,
        container: Container?,
        runtime: Runtime = selection(context, appId),
    ): Intent? {
        if (!isAvailable()) return null
        val permitsArm64Ec = runtime == Runtime.STEAM_CLIENT || runtime == Runtime.INSTALLED_PROTON
        if (ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.STEAM) {
            return steamPlayIntent(context, appId, container, permitsArm64Ec)?.apply {
                putExtra("wineDesktop", wineDesktop(context, appId, runtime))
                putExtra("buranFramegen2x", framegenEnabled(context, appId))
            }
        }
        val folder = CustomGameScanner.getFolderPathFromAppId(appId)
            ?.let { File(it).canonicalFile }?.takeIf { it.isDirectory } ?: return null
        if (container?.getExtra("gameRuntime") == "html5" ||
            container?.platform.equals(Container.PLATFORM_ANDROID, ignoreCase = true)
        ) return null

        val mode = container?.getExtra(LocalContainerLaunch.EXTRA_MODE).orEmpty()
        val files = when (mode) {
            LocalContainerLaunch.MODE_INSTALLED_EXE_C -> {
                val profile = checkNotNull(container)
                val drive = File(profile.rootDir, ".wine/drive_c").canonicalFile
                val relative = profile.getExtra(LocalContainerLaunch.EXTRA_TARGET).ifBlank { profile.executablePath }
                    .replace('\\', '/').trimStart('/')
                installedGameFiles(drive, relative)
            }
            "" -> {
                // The configured A: mapping is also the source when a local profile overrides it.
                val gameRoot = container?.let { ContainerUtils.getADrivePath(it.drives) }
                    ?.let { File(it).canonicalFile } ?: folder
                val relative = container?.executablePath.orEmpty().replace('\\', '/').ifBlank {
                    CustomGameScanner.findUniqueExeRelativeToFolder(gameRoot) ?: return null
                }
                resolveGameExecutable(gameRoot, relative)?.let { GameFiles(gameRoot, it) }
            }
            else -> null // An installer must complete through the existing Winlator path.
        } ?: return null

        val args = if (mode == LocalContainerLaunch.MODE_INSTALLED_EXE_C && container != null) {
            ItchArgCodec.decode(container.getExtra(LocalContainerLaunch.EXTRA_LAUNCH_ARGS))
        } else emptyList()
        return playIntent(context, appId, files, folder.name, args, permitsArm64Ec)?.apply {
            putExtra("wineDesktop", wineDesktop(context, appId, runtime))
            putExtra("buranFramegen2x", framegenEnabled(context, appId))
        }
    }

    private fun steamPlayIntent(context: Context, appId: String, container: Container?, permitsArm64Ec: Boolean): Intent? {
        if (container?.platform.equals(Container.PLATFORM_ANDROID, ignoreCase = true)) return null
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)
        val info = SteamService.getAppInfoOf(gameId) ?: return null
        if (!SteamService.isAppInstalled(gameId)) return null
        val root = File(SteamService.getAppDirPath(gameId)).canonicalFile
        if (!root.isDirectory || SteamService.isInstallRootOrAbove(root)) return null
        val executable = SteamService.resolveConfiguredExecutable(gameId, container?.executablePath.orEmpty())
        val launchInfos = SteamService.getWindowsLaunchInfos(gameId)
        val paths = listOf(executable) + launchInfos.map { it.executable }
        val target = paths.firstNotNullOfOrNull {
            resolveGameExecutable(root, it.trim().removeSurrounding("\"").replace('\\', '/'), ignoreCase = true)
        } ?: return null
        val relative = target.relativeTo(root).invariantSeparatorsPath
        val launchInfo = launchInfos.firstOrNull {
            it.executable.replace('\\', '/').trim().trim('/').equals(relative, ignoreCase = true)
        }
        val args = ProcessHelper.splitCommand(SteamUtils.launchConfigArguments(launchInfo, relative)).toList() +
            ProcessHelper.splitCommand(container?.execArgs.orEmpty())
        val workingDirectory = launchInfo?.workingDir.orEmpty().trim().replace('\\', '/').trimEnd('/')
        return playIntent(context, appId, GameFiles(root, target), info.name, args, permitsArm64Ec)?.apply {
            putExtra("steamAppId", gameId)
            if (workingDirectory.isNotEmpty()) putExtra("workingDirectory", workingDirectory)
            container?.rootDir?.let { File(it, ".wine/drive_c/users/xuser").canonicalFile }
                ?.takeIf { it.isDirectory }?.let { putExtra("steamUserDirectory", it.path) }
        }
    }

    private fun playIntent(
        context: Context,
        appId: String,
        files: GameFiles,
        title: String,
        args: List<String>,
        permitsArm64Ec: Boolean,
    ): Intent? {
        val kind = files.executable.inputStream().use(WindowsExecutableInspector::inspect).kind
        if (kind != ExecutableKind.WINDOWS_64_PE &&
            (kind != ExecutableKind.WINDOWS_32_PE || (!permitsArm64Ec && Build.SUPPORTED_32_BIT_ABIS.isEmpty()))
        ) return null

        return Intent(ACTION).setClassName(context.packageName, ACTIVITY)
            .putExtra("installKey", appId)
            .putExtra("gameDirectory", files.root.path)
            .putExtra("executable", files.executable.relativeTo(files.root).invariantSeparatorsPath)
            .putExtra("title", title)
            .apply {
                if (args.isNotEmpty()) putStringArrayListExtra("gameArguments", ArrayList(args))
            }
    }

    private data class GameFiles(val root: File, val executable: File)

    private fun installedGameFiles(drive: File, relative: String): GameFiles? {
        val parts = relative.split('/')
        val depth = when (parts.first().lowercase(Locale.ROOT)) {
            "program files", "program files (x86)", "games", "gog games" -> 2
            "windows", "users", "programdata", "temp", "tmp", "\$recycle.bin" -> return null
            else -> 1
        }
        if (parts.size <= depth) return null
        val executable = resolveGameExecutable(drive, relative) ?: return null
        val root = File(drive, parts.take(depth).joinToString("/")).canonicalFile
        return GameFiles(root, executable).takeIf {
            root != drive && root.toPath().startsWith(drive.toPath()) && executable.toPath().startsWith(root.toPath())
        }
    }
}

internal fun resolveGameExecutable(root: File, relative: String, ignoreCase: Boolean = false): File? {
    if (relative.split('/').any { it.isEmpty() || it == "." || it == ".." || ':' in it || '\u0000' in it }) {
        return null
    }
    val target = if (ignoreCase) {
        FileUtils.findFileCaseInsensitive(root, relative)?.canonicalFile ?: return null
    } else File(root, relative).canonicalFile
    return target.takeIf {
        it.isFile && it.canRead() && it.extension.equals("exe", ignoreCase = true) &&
            it.toPath().startsWith(root.toPath())
    }
}
