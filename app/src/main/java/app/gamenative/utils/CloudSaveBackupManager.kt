package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * On-device snapshots of local save files, taken *before* a cloud sync replaces or deletes them, plus a
 * restore path that is itself undoable.
 *
 * Facts that are easy to get wrong when wiring this into a store:
 * - **Storage layout.** `filesDir/save_backups/<provider>_<gameId>/<yyyyMMdd-HHmmss-SSS>.zip` with a
 *   matching `.json` sidecar next to it. The archive holds `files/<rootId>/<relative path>` entries only,
 *   so it is self-contained: restoring needs no container, no transfer UI and no network.
 * - **Sidecar schema version is [SIDECAR_VERSION] (1).** The sidecar is a versioned JSON document with
 *   `version`, `provider`, `gameId`, `createdAt`, `kind`, `fingerprint`,
 *   `sizeBytes`, `fileCount` and `roots` (`id`, absolute `absolutePath`, `wholeDir`). Sidecars with any
 *   other version are ignored by [listBackups]/[restore], so a schema change must bump the version.
 * - **Retention.** `PRE_SYNC` snapshots keep [backupsToKeep] (`PrefManager.saveBackupsToKeep`, default 3)
 *   per game. The `PRE_RESTORE` safety snapshot that [restore] writes is stored in the same folder but
 *   pruned with a limit of 1, i.e. it does not consume the user's `PRE_SYNC` budget. Setting the
 *   preference to 0 disables `PRE_SYNC` snapshots only: [restore] still snapshots first and stays undoable.
 *   `MANUAL` snapshots are retained until the player deletes them.
 * - **"Skipped" means no snapshot exists.** [backup] and [BackupSession.ensure] return `false` when there
 *   is nothing to save, when the file set exceeds [MAX_TOTAL_BYTES] (512 MB), when free space is below
 *   twice the snapshot size, when the fingerprint matches the last snapshot of the same kind, or when
 *   retention is off. Callers must never assume that a snapshot was written.
 * - **Ordering.** Call [backup]/[BackupSession.ensure] *before* downloading, overwriting or deleting local
 *   files, never after. [restore] is the exception: it snapshots the current state itself first.
 *
 * Cloud providers: STEAM, GOG and EPIC. Upstream also wires EA and Rockstar; this fork has no EA,
 * Rockstar or Amazon cloud-save sync, so those provider constants are deliberately absent rather than
 * left behind as dead entries. A store that grows cloud saves later only needs to add its constant and a
 * call site; the archive format does not care which provider wrote it.
 */
object CloudSaveBackupManager {
    const val PROVIDER_STEAM = "steam"
    const val PROVIDER_GOG = "gog"
    const val PROVIDER_EPIC = "epic"
    const val PROVIDER_CUSTOM = "custom"

    enum class Kind { PRE_SYNC, PRE_RESTORE, MANUAL }

    /** [files] == null means every regular file under [dir] (recursive). */
    data class BackupRoot(val id: String, val dir: File, val files: List<File>? = null)

    data class SaveBackup(
        val provider: String,
        val gameId: String,
        val file: File,
        val createdAt: Long,
        val sizeBytes: Long,
        val fileCount: Int,
        val kind: Kind,
        val folder: String? = null,
    )

    class BackupSession internal constructor(
        private val context: Context,
        private val provider: String,
        private val gameId: String,
        private val roots: () -> List<BackupRoot>,
    ) {
        private val mutex = Mutex()
        private var result: Boolean? = null

        suspend fun ensure(): Boolean = mutex.withLock {
            result?.let { return@withLock it }
            val written = try {
                val resolved = withContext(Dispatchers.IO) { roots() }
                backup(context, provider, gameId, resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Save backup roots failed for $provider/$gameId")
                false
            }
            result = written
            written
        }
    }

    private sealed interface Outcome {
        data class Written(val file: File) : Outcome
        data object Skipped : Outcome
        data object SkippedUnsafe : Outcome
        data object Failed : Outcome
    }

    @Serializable
    private data class SidecarRoot(val id: String, val absolutePath: String, val wholeDir: Boolean)

    @Serializable
    private data class Sidecar(
        val version: Int,
        val provider: String,
        val gameId: String,
        val createdAt: Long,
        val kind: String,
        val fingerprint: String,
        val sizeBytes: Long,
        val fileCount: Int,
        val roots: List<SidecarRoot>,
    )

    private class Collected(val rootId: String, val relPath: String, val path: Path, val size: Long, val mtime: Long)

    private class ResolvedRoot(val id: String, val path: Path, val wholeDir: Boolean, val files: List<Collected>)

    private const val TAG = "SaveBackups"
    private const val SIDECAR_VERSION = 1
    private const val DIR_NAME = "save_backups"
    private const val FILES_PREFIX = "files/"
    private const val CUSTOM_ROOT_ID = "saves"
    private const val ZIP_EXT = ".zip"
    private const val JSON_EXT = ".json"
    private const val PART_EXT = ".part"

    /** Snapshot ceiling, unchanged from upstream: a larger save set is skipped instead of archived. */
    internal const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    private const val DEFAULT_KEEP = 3

    private val lock = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Test seam: production reads [File.usableSpace]. Tests point this at a small value so the low-space
     * guard can be exercised without filling a real device.
     */
    internal var freeSpaceProvider: (File) -> Long = { it.usableSpace }

    /**
     * Test seam: invoked before every archive entry is written. Production leaves it `null`; tests set it
     * to throw and assert that a failed write leaves no backup, sidecar or `.part` file behind.
     */
    internal var beforeWriteEntry: (() -> Unit)? = null

    /** Number of PRE_SYNC backups kept per game; reads PrefManager.saveBackupsToKeep (Int, default 3). */
    fun backupsToKeep(): Int = try {
        PrefManager.saveBackupsToKeep.coerceAtLeast(0)
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Could not read save backup count, using default")
        DEFAULT_KEEP
    }

    fun session(context: Context, provider: String, gameId: String, roots: () -> List<BackupRoot>): BackupSession =
        BackupSession(context, provider, gameId, roots)

    /** Snapshot the current local files. Returns true when a new backup was written, false when skipped or failed. */
    suspend fun backup(
        context: Context,
        provider: String,
        gameId: String,
        roots: List<BackupRoot>,
        kind: Kind = Kind.PRE_SYNC,
    ): Boolean = lock.withLock {
        withContext(Dispatchers.IO) { backupLocked(context, provider, gameId, roots, kind) } is Outcome.Written
    }

    /** Newest first. Cheap: reads sidecar json only. */
    fun listBackups(context: Context, provider: String, gameId: String): List<SaveBackup> = try {
        readBackups(gameDir(context, provider, gameId), provider, gameId).map { it.first }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Listing save backups failed for $provider/$gameId")
        emptyList()
    }

    /** Writes save files relative to the selected folder, without local backup metadata. */
    internal suspend fun exportCustomBackup(
        context: Context,
        gameId: String,
        fileName: String,
        openOutput: () -> OutputStream?,
    ): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val backup = listBackups(context, PROVIDER_CUSTOM, gameId)
                    .firstOrNull { it.file.name == fileName } ?: return@withContext false
                val prefix = "${FILES_PREFIX}${CUSTOM_ROOT_ID}/"
                ZipFile(backup.file).use { zip ->
                    val entries = zip.entries().toList()
                    require(entries.isNotEmpty() && entries.all { entry ->
                        !entry.isDirectory && entry.name.startsWith(prefix) &&
                            validCustomSavePath(entry.name.removePrefix(prefix))
                    })
                    val output = openOutput() ?: return@withContext false
                    ZipOutputStream(output.buffered()).use { out ->
                        for (entry in entries) {
                            out.putNextEntry(ZipEntry(entry.name.removePrefix(prefix)).apply { time = entry.time })
                            zip.getInputStream(entry).use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not export custom save backup")
                false
            }
        }
    }

    internal fun customSaveRoot(base: File, relative: String): BackupRoot? {
        if (!validCustomSavePath(relative)) return null
        val canonicalBase = base.canonicalFile
        val folder = File(canonicalBase, relative)
        return if (folder.isDirectory && folder.canonicalFile == folder) BackupRoot(CUSTOM_ROOT_ID, folder)
        else null
    }

    private fun validCustomSavePath(relative: String): Boolean =
        relative.split('/').none { part ->
            part.isBlank() || part == "." || part == ".." || part.any { it == ':' || it == '\\' || it.isISOControl() }
        }

    /** Takes a PRE_RESTORE backup of the current state first, then replaces the current save set with the backup's contents. */
    suspend fun restore(context: Context, backup: SaveBackup): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                restoreLocked(context, backup)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Save restore failed for ${backup.provider}/${backup.gameId} from ${backup.file.name}")
                false
            }
        }
    }

    fun deleteBackup(backup: SaveBackup): Boolean = try {
        val zipDeleted = !backup.file.exists() || backup.file.delete()
        val sidecar = sidecarOf(backup.file)
        val jsonDeleted = !sidecar.exists() || sidecar.delete()
        zipDeleted && jsonDeleted
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Deleting save backup ${backup.file} failed")
        false
    }

    private fun backupLocked(
        context: Context,
        provider: String,
        gameId: String,
        roots: List<BackupRoot>,
        kind: Kind,
        restoreSource: File? = null,
    ): Outcome {
        try {
            if (kind == Kind.PRE_SYNC && backupsToKeep() == 0) return Outcome.Skipped
            val resolved = roots.mapNotNull { root ->
                resolveRoot(root) ?: if (restoreSource != null) throw IOException("Cannot resolve ${root.dir}") else null
            }
            val files = resolved.flatMap { it.files }
            if (files.isEmpty()) return Outcome.Skipped
            val total = files.sumOf { it.size }
            if (total > MAX_TOTAL_BYTES) {
                Timber.tag(TAG).w("Save backup skipped for $provider/$gameId: $total bytes exceeds the limit")
                return Outcome.SkippedUnsafe
            }
            val dir = gameDir(context, provider, gameId)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
            if (kind == Kind.PRE_SYNC) prune(dir, provider, gameId, kind, restoreSource)
            if (freeSpaceProvider(context.filesDir) < total * 2) {
                Timber.tag(TAG).w("Save backup skipped for $provider/$gameId: not enough free space for $total bytes")
                return Outcome.SkippedUnsafe
            }
            val fingerprint = fingerprint(resolved, files)
            val latest = if (restoreSource == null) readBackups(dir, provider, gameId).firstOrNull { it.first.kind == kind } else null
            if (latest?.second?.fingerprint == fingerprint) {
                Timber.tag(TAG).i("Save backup skipped for $provider/$gameId: unchanged since the last ${kind.name} backup")
                return Outcome.Skipped
            }
            dir.listFiles()?.filter { it.name.endsWith(PART_EXT) }?.forEach { it.delete() }

            var createdAt = System.currentTimeMillis()
            while (File(dir, stamp(createdAt) + ZIP_EXT).exists() || File(dir, stamp(createdAt) + JSON_EXT).exists()) createdAt++
            val base = stamp(createdAt)
            val zip = File(dir, base + ZIP_EXT)
            val sidecar = File(dir, base + JSON_EXT)
            val zipPart = File(dir, base + ZIP_EXT + PART_EXT)
            val sidecarPart = File(dir, base + JSON_EXT + PART_EXT)
            try {
                ZipOutputStream(zipPart.outputStream().buffered()).use { out ->
                    for (file in files) {
                        beforeWriteEntry?.invoke()
                        val entry = ZipEntry("$FILES_PREFIX${file.rootId}/${file.relPath}")
                        entry.time = file.mtime
                        out.putNextEntry(entry)
                        Files.newInputStream(file.path, LinkOption.NOFOLLOW_LINKS).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                val meta = Sidecar(
                    version = SIDECAR_VERSION,
                    provider = provider,
                    gameId = gameId,
                    createdAt = createdAt,
                    kind = kind.name,
                    fingerprint = fingerprint,
                    sizeBytes = total,
                    fileCount = files.size,
                    roots = resolved.map { SidecarRoot(it.id, it.path.toString(), it.wholeDir) },
                )
                sidecarPart.writeText(json.encodeToString(Sidecar.serializer(), meta))
                if (!zipPart.renameTo(zip)) throw IOException("Cannot finalize ${zip.name}")
                if (!sidecarPart.renameTo(sidecar)) {
                    zip.delete()
                    throw IOException("Cannot finalize ${sidecar.name}")
                }
            } finally {
                zipPart.delete()
                sidecarPart.delete()
            }
            Timber.tag(TAG).i("Save backup ${kind.name} for $provider/$gameId: ${files.size} file(s), $total bytes -> ${zip.name}")
            prune(dir, provider, gameId, kind, restoreSource, newest = zip.takeIf { restoreSource != null })
            return Outcome.Written(zip)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Save backup failed for $provider/$gameId")
            return Outcome.Failed
        }
    }

    private fun prune(dir: File, provider: String, gameId: String, kind: Kind, restoreSource: File?, newest: File? = null) {
        if (kind == Kind.MANUAL) return
        val keep = if (kind == Kind.PRE_SYNC) backupsToKeep() else 1
        // The active undo snapshot must survive even if the system clock has moved backwards.
        readBackups(dir, provider, gameId)
            .map { it.first }
            .filter { it.kind == kind && it.file != newest }
            .drop(if (newest == null) keep else (keep - 1).coerceAtLeast(0))
            .filter { it.file.absoluteFile != restoreSource?.absoluteFile }
            .forEach { deleteBackup(it) }
    }

    private class RestorePlan(val roots: Map<String, Path>, val wholeDir: Map<String, Boolean>, val entries: Map<Path, String>)

    private fun restoreLocked(context: Context, backup: SaveBackup): Boolean {
        val sidecarFile = sidecarOf(backup.file)
        if (!backup.file.isFile || !sidecarFile.isFile) {
            Timber.tag(TAG).w("Save restore refused: ${backup.file.name} or its sidecar is missing")
            return false
        }
        val meta = json.decodeFromString(Sidecar.serializer(), sidecarFile.readText())
        val plan = planRestore(backup.file, meta)

        val preRestoreRoots = meta.roots.map { root ->
            val dir = File(root.absolutePath)
            if (root.wholeDir) {
                BackupRoot(root.id, dir)
            } else {
                val rootPath = plan.roots.getValue(root.id)
                BackupRoot(root.id, dir, plan.entries.keys.filter { it.startsWith(rootPath) }.map { it.toFile() })
            }
        }
        // A path/size/mtime fingerprint is sufficient for routine deduplication, but a restore
        // needs the exact current contents as its rollback source.
        val undo = when (
            val outcome = backupLocked(context, meta.provider, meta.gameId, preRestoreRoots, Kind.PRE_RESTORE, backup.file)
        ) {
            is Outcome.Written -> outcome.file
            Outcome.Skipped -> null // None of the files this restore can replace or remove existed.
            Outcome.Failed, Outcome.SkippedUnsafe -> {
                Timber.tag(TAG).w("Save restore aborted: could not back up the current saves first")
                return false
            }
        }
        val undoPlan = undo?.let {
            val undoMeta = json.decodeFromString(Sidecar.serializer(), sidecarOf(it).readText())
            planRestore(it, undoMeta)
        }
        val originals = undoPlan?.entries.orEmpty()
        val changedPaths = mutableSetOf<Path>()

        try {
            for (destination in plan.entries.keys) {
                if (destination !in originals && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("No rollback copy for $destination")
                }
            }
            restoreEntries(backup.file, plan.roots, plan.entries, changedPaths)
            for ((id, root) in plan.roots) {
                if (plan.wholeDir[id] != true || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue
                for (extra in walkRegularFiles(root) { file, _ -> file }) {
                    if (extra in plan.entries) continue
                    if (extra !in originals) throw IOException("No rollback copy for $extra")
                    if (Files.deleteIfExists(extra)) changedPaths.add(extra)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Save restore failed midway for ${meta.provider}/${meta.gameId}, rolling back")
            rollBack(plan, undo, undoPlan, changedPaths)
            throw e
        }
        Timber.tag(TAG).i("Restored ${plan.entries.size} save file(s) for ${meta.provider}/${meta.gameId} from ${backup.file.name}")
        if (backup.kind == Kind.PRE_RESTORE) deleteBackup(backup)
        return true
    }

    private fun rollBack(plan: RestorePlan, undo: File?, undoPlan: RestorePlan?, changedPaths: Set<Path>) {
        for (created in changedPaths - undoPlan?.entries.orEmpty().keys) {
            try {
                val root = plan.roots.getValue(rootOf(plan.roots, created))
                requireRealPath(root)
                checkNoSymlinks(root, created)
                Files.deleteIfExists(created)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Could not remove partially restored save $created")
            }
        }
        if (undo != null && undoPlan != null) {
            try {
                val changedEntries = undoPlan.entries.filterKeys { it in changedPaths }
                restoreEntries(undo, undoPlan.roots, changedEntries)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Could not roll back saves from ${undo.name}")
            }
        }
    }

    private fun planRestore(zipFile: File, meta: Sidecar): RestorePlan {
        val roots = meta.roots.associate { it.id to Paths.get(it.absolutePath).normalize() }
        val wholeDir = meta.roots.associate { it.id to it.wholeDir }
        roots.values.forEach { requireRealPath(it) }
        val planned = LinkedHashMap<Path, String>()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith(FILES_PREFIX)) continue
                val relativeEntry = entry.name.removePrefix(FILES_PREFIX).replace('\\', '/')
                val rootId = roots.keys.filter { relativeEntry.startsWith("$it/") }.maxByOrNull { it.length }
                    ?: throw IOException("Backup entry has no recorded root: ${entry.name}")
                val relativePath = relativeEntry.substring(rootId.length + 1).trimStart('/')
                if (relativePath.isBlank()) continue
                val root = roots.getValue(rootId)
                val destination = root.resolve(relativePath).normalize()
                if (!destination.startsWith(root) || destination == root) throw IOException("Backup entry escapes its root: ${entry.name}")
                checkNoSymlinks(root, destination)
                if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup entry target is a directory: $destination")
                planned[destination] = entry.name
            }
        }
        return RestorePlan(roots, wholeDir, planned)
    }

    private fun restoreEntries(
        zipFile: File,
        roots: Map<String, Path>,
        entries: Map<Path, String>,
        changedPaths: MutableSet<Path>? = null,
    ) {
        val now = FileTime.fromMillis(System.currentTimeMillis())
        ZipFile(zipFile).use { zip ->
            for ((destination, entryName) in entries) {
                val entry = zip.getEntry(entryName) ?: throw IOException("Backup entry vanished: $entryName")
                val parent = destination.parent ?: throw IOException("No parent for $destination")
                val root = roots.getValue(rootOf(roots, destination))
                requireRealPath(root)
                checkNoSymlinks(root, destination)
                Files.createDirectories(parent)
                val temp = Files.createTempFile(parent, ".restore-", PART_EXT)
                try {
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { input.copyTo(it) }
                    }
                    try {
                        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        // A failed non-atomic replacement may already have changed the destination.
                        changedPaths?.add(destination)
                        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING)
                    }
                    changedPaths?.add(destination)
                    val modified = if (changedPaths == null) entry.lastModifiedTime ?: now else now
                    Files.setLastModifiedTime(destination, modified)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
        }
    }

    private fun rootOf(roots: Map<String, Path>, destination: Path): String =
        roots.entries.filter { destination.startsWith(it.value) }.maxBy { it.value.nameCount }.key

    private fun requireRealPath(root: Path) {
        var existing: Path? = root
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent
        if (existing == null) return
        if (!Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS) || existing.toRealPath() != existing) {
            throw IOException("Refusing to restore into a symlinked save root: $root")
        }
    }

    private fun checkNoSymlinks(root: Path, destination: Path) {
        var current: Path? = destination
        while (current != null && current.startsWith(root) && current != root) {
            if (Files.isSymbolicLink(current)) throw IOException("Refusing to write through a symlink: $current")
            current = current.parent
        }
    }

    private fun resolveRoot(root: BackupRoot): ResolvedRoot? {
        val given = root.dir.absoluteFile.toPath().normalize()
        val canonical = try {
            root.dir.canonicalFile.toPath()
        } catch (e: IOException) {
            return null
        }
        val explicit = root.files
        if (explicit == null) {
            val files = if (Files.isDirectory(canonical)) {
                walkRegularFiles(canonical) { file, attrs -> collect(root.id, canonical, file, attrs) }
            } else {
                emptyList()
            }
            return ResolvedRoot(root.id, canonical, true, files)
        }
        val files = explicit.mapNotNull { file ->
            val path = file.absoluteFile.toPath().normalize()
            val relative = when {
                path.startsWith(given) -> given.relativize(path)
                path.startsWith(canonical) -> canonical.relativize(path)
                else -> {
                    Timber.tag(TAG).w("Save backup ignores $path: outside root $given")
                    return@mapNotNull null
                }
            }
            if (relative.toString().isEmpty()) return@mapNotNull null
            val candidate = canonical.resolve(relative).normalize()
            if (!candidate.startsWith(canonical)) return@mapNotNull null
            var current: Path? = candidate
            while (current != null && current != canonical) {
                if (Files.isSymbolicLink(current)) {
                    Timber.tag(TAG).w("Save backup ignores $candidate: symlink in path")
                    return@mapNotNull null
                }
                current = current.parent
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
            collect(root.id, canonical, candidate)
        }.distinctBy { it.relPath }
        return ResolvedRoot(root.id, canonical, false, files)
    }

    private fun collect(rootId: String, root: Path, path: Path): Collected? {
        val attrs = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            return null
        }
        if (!attrs.isRegularFile) return null
        return collect(rootId, root, path, attrs)
    }

    private fun collect(rootId: String, root: Path, path: Path, attrs: BasicFileAttributes): Collected {
        val relPath = root.relativize(path).joinToString("/") { it.toString() }
        return Collected(rootId, relPath, path, attrs.size(), attrs.lastModifiedTime().toMillis())
    }

    private fun <T> walkRegularFiles(root: Path, transform: (Path, BasicFileAttributes) -> T): List<T> {
        val out = ArrayList<T>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out.add(transform(file, attrs))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
            },
        )
        return out
    }

    private fun fingerprint(roots: List<ResolvedRoot>, files: List<Collected>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        roots.map { "root:${it.id}|${it.path}|${it.wholeDir}" }.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update('\n'.code.toByte())
        }
        files.map { "${it.rootId}/${it.relPath}|${it.size}|${it.mtime}" }.sorted().forEach {
            digest.update(it.toByteArray())
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBackups(dir: File, provider: String, gameId: String): List<Pair<SaveBackup, Sidecar>> {
        val sidecars = dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_EXT) } ?: return emptyList()
        return sidecars.mapNotNull { sidecar ->
            val zip = File(dir, sidecar.name.removeSuffix(JSON_EXT) + ZIP_EXT)
            if (!zip.isFile) return@mapNotNull null
            val meta = try {
                json.decodeFromString(Sidecar.serializer(), sidecar.readText())
            } catch (e: Exception) {
                return@mapNotNull null
            }
            val kind = Kind.entries.firstOrNull { it.name == meta.kind } ?: return@mapNotNull null
            if (meta.version != SIDECAR_VERSION || meta.provider != provider || meta.gameId != gameId) return@mapNotNull null
            val folder = meta.roots.singleOrNull()?.absolutePath?.takeIf { provider == PROVIDER_CUSTOM }
            SaveBackup(provider, gameId, zip, meta.createdAt, meta.sizeBytes, meta.fileCount, kind, folder) to meta
        }.sortedWith(compareByDescending<Pair<SaveBackup, Sidecar>> { it.first.createdAt }.thenByDescending { it.first.file.name })
    }

    private fun gameDir(context: Context, provider: String, gameId: String): File =
        File(File(context.filesDir, DIR_NAME), "${safe(provider)}_${safe(gameId)}")

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun sidecarOf(zip: File): File = File(zip.parentFile, zip.name.removeSuffix(ZIP_EXT) + JSON_EXT)

    private fun stamp(millis: Long): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(millis))
}
