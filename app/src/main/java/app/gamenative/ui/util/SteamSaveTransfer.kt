package app.gamenative.ui.util

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import app.gamenative.service.SteamService
import app.gamenative.utils.FileUtils
import com.winlator.container.Container
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.stream.Collectors
import java.util.zip.CheckedInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.pathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

object SteamSaveTransfer {
    private const val ARCHIVE_VERSION = 5
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    @Serializable
    private data class SaveArchiveManifest(
        val version: Int = ARCHIVE_VERSION,
        val steamAppId: Int,
        val gameName: String,
        val exportedAt: Long,
        val roots: List<SaveRoot>,
    )

    @Serializable
    private data class SaveRoot(
        val rootId: String,
        val path: String,
    )

    private data class ResolvedSaveRoot(
        val rootId: String,
        val absolutePath: Path,
        val files: List<Path>,
    )

    private data class StagedSave(val destination: Path, val payload: Path, var backup: Path? = null)

    suspend fun exportSaves(
        context: Context,
        container: Container,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            val prefixToPath = makePrefixToPath(container, steamAppId)
            val roots = withContext(Dispatchers.IO) { resolveExportRoots(app, prefixToPath) }

            if (roots.isEmpty()) {
                SnackbarManager.show(context.getString(R.string.steam_save_export_no_saves_found))
                return false
            }

            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    ZipOutputStream(outputStream.buffered()).use { zip ->
                        val manifestRoots = roots.map { SaveRoot(rootId = it.rootId, path = it.absolutePath.pathString) }
                        val manifest = SaveArchiveManifest(
                            steamAppId = steamAppId,
                            gameName = app.name,
                            exportedAt = System.currentTimeMillis(),
                            roots = manifestRoots,
                        )

                        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                        zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                        zip.closeEntry()

                        roots.forEach { root ->
                            root.files.forEach { file ->
                                if (!Files.isRegularFile(file)) return@forEach
                                val relativePath = normalizeRelativePath(root.absolutePath.relativize(file).toString())
                                zip.putNextEntry(ZipEntry("$FILES_PREFIX${root.rootId}/$relativePath"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: throw IOException("Unable to open destination")
            }

            SnackbarManager.show(context.getString(R.string.steam_save_export_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to export Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_export_failed, e.message ?: "Unknown error"))
            false
        }
    }

    suspend fun importSaves(
        context: Context,
        container: Container,
        steamAppId: Int,
        uri: Uri,
    ): Boolean {
        return try {
            val app = SteamService.getAppInfoOf(steamAppId)
                ?: throw IOException("Steam app not found")
            withContext(Dispatchers.IO) {
                val archive = Files.createTempFile(context.cacheDir.toPath(), ".steam-saves-", ".zip")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        Files.newOutputStream(archive).use { output -> input.copyTo(output) }
                    } ?: throw IOException("Unable to open archive")
                    ZipFile(archive.toFile()).use { zip ->
                        val manifest = readArchiveManifest(zip)
                        if (manifest.steamAppId != steamAppId) {
                            throw IOException("Archive is for Steam app ${manifest.steamAppId}, expected $steamAppId")
                        }
                        if (manifest.roots.isEmpty()) {
                            throw IOException("Archive does not declare any save roots")
                        }
                        val prefixToPath = makePrefixToPath(container, steamAppId)
                        val rootMap = resolveImportRoots(app, manifest.roots, prefixToPath)
                        if (!restoreArchive(zip, rootMap)) {
                            throw IOException("No save files found in archive")
                        }
                    }
                } finally {
                    deleteTempFile(archive)
                }
            }

            SnackbarManager.show(context.getString(R.string.steam_save_import_success))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to import Steam saves for appId=$steamAppId")
            SnackbarManager.show(context.getString(R.string.steam_save_import_failed, e.message ?: "Unknown error"))
            false
        }
    }

    // -- Root resolution -------------------------------------------------------

    /**
     * Mirrors the file discovery logic in `SteamAutoCloud.getLocalUserFilesAsPrefixMap`:
     * 1. Walk UFS saveFilePatterns (GameInstall, WinMyDocuments, WinAppData*, etc.)
     * 2. Always scan SteamUserData recursively
     *
     * Returns only roots that have at least one file.
     */
    private fun resolveExportRoots(
        app: app.gamenative.data.SteamApp,
        prefixToPath: (String) -> String?,
    ): List<ResolvedSaveRoot> {
        val result = mutableListOf<ResolvedSaveRoot>()

        // 1) UFS patterns (skip SteamUserData — handled below)
        val savePatterns = app.ufs.saveFilePatterns.filter { it.root.isWindows }
        savePatterns
            .filter { it.root != PathType.SteamUserData }
            .forEach { pattern ->
                val rootPath = prefixToPath(pattern.root.name) ?: return@forEach
                val basePath = Paths.get(rootPath, pattern.substitutedPath)
                val files = findPatternFiles(basePath, pattern)
                if (files.isNotEmpty()) {
                    result += ResolvedSaveRoot(
                        rootId = patternRootId(pattern),
                        absolutePath = basePath,
                        files = files,
                    )
                }
            }

        // 2) SteamUserData — always scanned recursively (matches SteamAutoCloud behavior)
        prefixToPath(PathType.SteamUserData.name)?.let { userDataRoot ->
            val userDataPath = Paths.get(userDataRoot)
            val userDataFiles = findPatternFiles(
                userDataPath,
                SaveFilePattern(root = PathType.SteamUserData, path = "", pattern = "*", recursive = 5),
            )
            if (userDataFiles.isNotEmpty()) {
                result += ResolvedSaveRoot(
                    rootId = PathType.SteamUserData.name.lowercase(),
                    absolutePath = userDataPath,
                    files = userDataFiles,
                )
            }
        }

        return result
    }

    /**
     * For import: resolve each manifest root back to an absolute path.
     * Matches by rootId against known UFS patterns + SteamUserData.
     */
    private fun resolveImportRoots(
        app: app.gamenative.data.SteamApp,
        manifestRoots: List<SaveRoot>,
        prefixToPath: (String) -> String?,
    ): Map<String, Path> {
        val knownRoots = mutableMapOf<String, Path>()

        // Build lookup from UFS patterns
        app.ufs.saveFilePatterns
            .filter { it.root.isWindows }
            .filter { it.root != PathType.SteamUserData }
            .forEach { pattern ->
                val rootPath = prefixToPath(pattern.root.name) ?: return@forEach
                val id = patternRootId(pattern)
                knownRoots[id] = Paths.get(rootPath, pattern.substitutedPath)
            }

        // SteamUserData
        prefixToPath(PathType.SteamUserData.name)?.let { userDataRoot ->
            knownRoots[PathType.SteamUserData.name.lowercase()] = Paths.get(userDataRoot)
        }

        // Resolve manifest roots against known roots, fall back to stored path
        return manifestRoots.associate { mr ->
            mr.rootId to (knownRoots[mr.rootId] ?: Paths.get(mr.path))
        }
    }

    private fun findPatternFiles(basePath: Path, pattern: SaveFilePattern): List<Path> {
        if (!Files.exists(basePath)) return emptyList()
        val depth = if (pattern.recursive > 0) pattern.recursive else 5
        return FileUtils.findFilesRecursive(
            rootPath = basePath,
            pattern = pattern.pattern,
            maxDepth = depth,
        )
            .filter { Files.isRegularFile(it) }
            .collect(Collectors.toList())
    }

    private fun patternRootId(pattern: SaveFilePattern): String {
        val root = pattern.root.name.lowercase()
        val normalizedPath = pattern.path
            .replace('\\', '/')
            .ifBlank { "root" }
            .lowercase()
        return "$root/$normalizedPath"
    }

    // -- Archive I/O -----------------------------------------------------------

    private fun readArchiveManifest(zip: ZipFile): SaveArchiveManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY)?.takeUnless { it.isDirectory }
            ?: throw IOException("Missing save archive manifest")
        val manifest = ByteArrayOutputStream()
        readArchiveEntry(zip, entry, manifest)
        return json.decodeFromString(SaveArchiveManifest.serializer(), manifest.toByteArray().decodeToString())
    }

    @Synchronized
    internal fun restoreArchive(
        zip: ZipFile,
        rootMap: Map<String, Path>,
        replace: (Path, Path) -> Unit = ::replaceSave,
    ): Boolean {
        val staged = mutableListOf<StagedSave>()
        val committed = mutableListOf<StagedSave>()
        val seenEntries = mutableSetOf<String>()
        val destinations = mutableSetOf<Path>()
        val fileKeys = mutableSetOf<Any>()
        try {
            for (entry in zip.entries()) {
                if (!seenEntries.add(entry.name)) throw ZipException("Duplicate ZIP entry: ${entry.name}")
                if (entry.isDirectory) continue
                val destination = if (entry.name.startsWith(FILES_PREFIX)) resolveDestination(entry.name, rootMap) else null
                if (destination == null) {
                    readArchiveEntry(zip, entry, null)
                    continue
                }
                if (!destinations.add(destination)) throw ZipException("Multiple ZIP entries target $destination")
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    val attributes = Files.readAttributes(destination, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (!attributes.isRegularFile) throw IOException("Save destination is not a regular file: $destination")
                    val fileKey = attributes.fileKey()
                    if (fileKey != null && !fileKeys.add(fileKey)) {
                        throw ZipException("Multiple ZIP entries target the same file: $destination")
                    }
                }
                val payload = Files.createTempFile(destination.parent, ".steam-save-", ".tmp")
                staged.add(StagedSave(destination, payload))
                Files.newOutputStream(payload).use { readArchiveEntry(zip, entry, it) }
            }

            for (save in staged) {
                if (!Files.exists(save.destination, LinkOption.NOFOLLOW_LINKS)) continue
                if (!Files.isRegularFile(save.destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Save destination is not a regular file: ${save.destination}")
                }
                val backup = Files.createTempFile(save.destination.parent, ".steam-save-backup-", ".tmp")
                save.backup = backup
                Files.copy(
                    save.destination,
                    backup,
                    LinkOption.NOFOLLOW_LINKS,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }

            for (save in staged) {
                replace(save.payload, save.destination)
                committed.add(save)
            }
            return committed.isNotEmpty()
        } catch (error: Exception) {
            for (save in committed.asReversed()) {
                val backup = save.backup
                try {
                    if (backup == null) Files.deleteIfExists(save.destination) else replaceSave(backup, save.destination)
                } catch (rollbackError: Exception) {
                    // Keep the original contents available for recovery if rollback fails.
                    save.backup = null
                    error.addSuppressed(IOException("Could not restore ${save.destination} from $backup", rollbackError))
                }
            }
            throw error
        } finally {
            for (save in staged) {
                deleteTempFile(save.payload)
                save.backup?.let(::deleteTempFile)
            }
        }
    }

    private fun readArchiveEntry(zip: ZipFile, entry: ZipEntry, output: OutputStream?) {
        val crc = CRC32()
        val size = CheckedInputStream(zip.getInputStream(entry), crc).use { input ->
            if (output != null) input.copyTo(output) else input.skip(Long.MAX_VALUE)
        }
        if (size != entry.size || crc.value != entry.crc) {
            throw ZipException("Invalid size or CRC for ${entry.name}")
        }
    }

    private fun replaceSave(source: Path, destination: Path) {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun deleteTempFile(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            Timber.w(e, "Failed to remove temporary save file %s", path)
        }
    }

    private fun resolveDestination(
        entryName: String,
        rootMap: Map<String, Path>,
    ): Path? {
        val relativeEntry = entryName.removePrefix(FILES_PREFIX).replace('\\', '/')
        if (relativeEntry.indexOf('/') <= 0) return null

        val rootId = rootMap.keys
            .filter { relativeEntry.startsWith("$it/") }
            .maxByOrNull { it.length }
            ?: throw IOException("Archive save root not available: ${relativeEntry.substringBefore('/')}")
        val relativePath = normalizeRelativePath(relativeEntry.substring(rootId.length + 1))
        if (relativePath.isBlank()) return null

        val normalizedRoot = rootMap.getValue(rootId).toAbsolutePath().normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (destination == normalizedRoot || !destination.startsWith(normalizedRoot)) {
            throw IOException("Archive entry escapes save root: $entryName")
        }

        // Save roots may themselves be links supplied by the container. Resolve the root
        // first, then verify each parent before creating a directory or staging a save.
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) normalizedRoot.createDirectories()
        val realRoot = normalizedRoot.toRealPath()
        var parent = realRoot
        for (component in normalizedRoot.relativize(destination.parent)) {
            val child = parent.resolve(component)
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(child)
            parent = child.toRealPath()
            if (!parent.startsWith(realRoot) || !Files.isDirectory(parent)) {
                throw IOException("Archive entry escapes save root: $entryName")
            }
        }
        return parent.resolve(destination.fileName)
    }

    // -- Helpers ---------------------------------------------------------------

    private fun makePrefixToPath(container: Container, appId: Int): (String) -> String? = { prefix ->
        resolveRootBasePath(container, appId, PathType.from(prefix))?.pathString
    }

    private fun resolveRootBasePath(
        container: Container,
        appId: Int,
        rootType: PathType,
    ): Path? {
        val accountId = when (rootType) {
            PathType.SteamUserData -> resolveSteamAccountId(container, appId) ?: return null
            else -> 0L
        }
        return Paths.get(rootType.toAbsPath(container, appId, accountId))
    }

    private fun resolveSteamAccountId(container: Container, appId: Int): Long? {
        SteamService.userSteamId?.accountID?.toLong()?.let { return it }
        PrefManager.steamUserAccountId.takeIf { it != 0 }?.toLong()?.let { return it }

        val userdataRoot = Paths.get(
            container.rootDir.absolutePath,
            ".wine/drive_c/Program Files (x86)/Steam/userdata",
        )
        if (!Files.isDirectory(userdataRoot)) return null

        val matchingAccountIds = Files.list(userdataRoot).use { children ->
            children
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { candidate -> candidate.all(Char::isDigit) }
                .filter { candidate ->
                    val appRoot = userdataRoot.resolve(candidate).resolve(appId.toString())
                    Files.isDirectory(appRoot)
                }
                .sorted()
                .collect(Collectors.toList())
        }

        if (matchingAccountIds.size > 1) {
            Timber.w(
                "Multiple Steam userdata accounts found for appId=$appId; using ${matchingAccountIds.first()} for save transfer",
            )
        }

        return matchingAccountIds.firstOrNull()?.toLongOrNull()
    }

    private fun normalizeRelativePath(value: String): String =
        value.replace('\\', '/').trimStart('/').trim()
}
