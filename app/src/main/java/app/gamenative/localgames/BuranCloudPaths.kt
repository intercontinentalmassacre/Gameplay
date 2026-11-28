package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

internal object BuranCloudPaths {
    private const val PENDING_EXITS = "buran_pending_steam_cloud_exits"

    private fun pendingExits(context: Context) = context.getSharedPreferences(PENDING_EXITS, Context.MODE_PRIVATE)
    private fun pendingKey(appId: String, accountId: Long) = "$accountId:$appId"

    fun recordPendingExit(context: Context, appId: String, accountId: Long, resolver: Resolver) {
        pendingExits(context).edit().putString(pendingKey(appId, accountId), resolver.identity).apply()
    }

    fun pendingExitMatches(context: Context, appId: String, accountId: Long, resolver: Resolver?): Boolean {
        val stored = pendingExits(context).getString(pendingKey(appId, accountId), null)
        return stored == null || stored == resolver?.identity
    }

    fun clearPendingExit(context: Context, appId: String, accountId: Long, resolver: Resolver) {
        val prefs = pendingExits(context)
        val key = pendingKey(appId, accountId)
        if (prefs.getString(key, null) == resolver.identity) prefs.edit().remove(key).apply()
    }

    private val profileFolders = mapOf(
        PathType.WinAppDataLocalLow to "AppData/LocalLow",
        PathType.WinAppDataLocal to "AppData/Local",
        PathType.WinAppDataRoaming to "AppData/Roaming",
        PathType.WinMyDocuments to "Documents",
        PathType.WinSavedGames to "Saved Games",
    )

    fun prefixToPath(context: Context, appId: String, steamAccountId: Long, intent: Intent): Resolver? {
        val steamAppId = intent.getIntExtra("steamAppId", 0)
        if (steamAppId <= 0 || steamAccountId <= 0) return null
        val gameInstall = intent.getStringExtra("gameDirectory")?.let(::File) ?: return null
        val container = ContainerUtils.getOrCreateContainer(context, appId, installWinePrefix = false)
        val prefix = File(container.rootDir, ".wine/drive_c").canonicalFile
        val remote = prefix.resolve("Program Files (x86)/Steam/userdata/$steamAccountId/$steamAppId/remote")
            .canonicalFile.takeIf { it.toPath().startsWith(prefix.toPath()) && it.toPath().none { part -> ':' in part.toString() } }
            ?: return null
        if (!remote.isDirectory && !remote.mkdirs()) return null
        intent.putExtra("steamRemoteDirectory", remote.path)
        val profile = (intent.getStringExtra("steamUserDirectory")?.let(::File)
            ?: prefix.resolve("users/${ImageFs.USER}")).canonicalFile
            .takeIf { it.toPath().startsWith(prefix.toPath()) && it.toPath().none { part -> ':' in part.toString() } }
            ?.takeIf { it.isDirectory || it.mkdirs() }
        if (profile != null) intent.putExtra("steamUserDirectory", profile.path)
        val patterns = SteamService.getAppInfoOf(steamAppId)?.ufs?.saveFilePatterns ?: return null
        return resolver(gameInstall, remote, profile, patterns)
    }

    internal fun resolver(
        gameInstall: File,
        steamRemote: File,
        profile: File?,
        patterns: List<SaveFilePattern>,
    ): Resolver? {
        val windowsPatterns = patterns.filter { it.root.isWindows }
        val roots = mutableMapOf(
            PathType.GameInstall to gameInstall.canonicalFile,
            PathType.SteamUserData to steamRemote.canonicalFile,
        )
        if (roots.values.any { !it.isDirectory }) return null
        for (pattern in windowsPatterns) {
            val root = pattern.root
            if (root in roots) continue
            val relative = profileFolders[root] ?: return null
            val base = profile?.canonicalFile ?: return null
            val directory = base.resolve(relative).canonicalFile
            if (!base.isDirectory || !directory.toPath().startsWith(base.toPath()) ||
                directory.toPath().any { ':' in it.toString() } ||
                (!directory.isDirectory && !directory.mkdirs())) return null
            roots[root] = directory
        }
        val aliases = windowsPatterns.filter { it.uploadRoot !in roots }
            .groupBy({ it.uploadRoot }, { it.root })
            .mapNotNull { (key, values) -> values.distinct().singleOrNull()?.let { key to it } }.toMap()
        return Resolver(roots, aliases)
    }

    class Resolver(
        private val roots: Map<PathType, File>,
        private val aliases: Map<PathType, PathType>,
    ) : (String) -> String {
        val identity: String by lazy {
            val parts = roots.toSortedMap().map { (type, file) -> "${type.name}=${file.path}" } +
                aliases.toSortedMap().map { (from, to) -> "${from.name}=${to.name}" }
            MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u0000").toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        override fun invoke(prefix: String): String {
            val kind = PathType.from(prefix)
            val root = roots[kind] ?: aliases[kind]?.let(roots::get)
                ?: throw IllegalArgumentException("Unsupported Buran Steam Cloud root: $prefix")
            return root.path + File.separator
        }

        fun validate(path: Path): Path {
            val realPath = path.toFile().canonicalFile.toPath()
            require(path.isAbsolute && path.none { it.toString() == ".." } && roots.values.any { root ->
                val base = root.toPath()
                path.toAbsolutePath().normalize().startsWith(base) && realPath.startsWith(base)
            }) { "Steam Cloud path is outside the Buran save folders: $path" }
            return path
        }
    }
}
