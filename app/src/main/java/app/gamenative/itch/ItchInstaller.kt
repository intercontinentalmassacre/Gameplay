package app.gamenative.itch

import app.gamenative.mods.ModArchiveExtractor
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** One file that belongs to the delivered build (not created by the player). */
@Serializable
data class ItchDeliveryFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

@Serializable
data class ItchDeliveryManifest(
    val files: List<ItchDeliveryFile> = emptyList(),
    val totalBytes: Long = 0L,
) {
    fun relativePaths(): Set<String> = files.mapTo(HashSet()) { it.path }
}

/**
 * Everything needed to launch one installed build, plus the delivery manifest
 * used to tell player-created files (saves) apart from files that came from the
 * ZIP. Written only after the whole tree has been extracted and verified, so its
 * presence marks a complete version.
 */
@Serializable
data class ItchVersionMeta(
    val versionId: String,
    val uploadId: Long,
    val buildId: Long? = null,
    val userVersion: String? = null,
    val fileUpdatedAt: String? = null,
    val fileSizeBytes: Long = 0L,
    val launchTarget: String = "",
    val launchArgs: List<String> = emptyList(),
    val manifest: ItchDeliveryManifest = ItchDeliveryManifest(),
    val createdAtMs: Long = 0L,
)

@Serializable
data class ItchInstallState(
    val activeVersionId: String,
    val previousVersionId: String? = null,
    val launchTarget: String,
    val launchArgs: List<String> = emptyList(),
    val installedAtMs: Long = 0L,
)

enum class ItchUpdatePhase {
    /** Files are being fetched/extracted; nothing in the active install was touched. */
    STAGING,

    /** The new version tree is complete and a swap is in progress. */
    PREPARED,
}

@Serializable
data class ItchUpdateJournal(
    val phase: ItchUpdatePhase,
    val targetVersionId: String,
    val previousVersionId: String?,
    val startedAtMs: Long,
)

data class ItchPlayerCarryResult(
    val copied: Int = 0,
    val conflicts: Int = 0,
    val copiedBytes: Long = 0L,
)

data class ItchRecoveryResult(
    val state: ItchInstallState? = null,
    val recovered: Boolean = false,
    val discardedVersionIds: List<String> = emptyList(),
    val message: String = "",
)

data class ItchExecutableCandidate(
    val path: String,
    val sizeBytes: Long,
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/**
 * Versioned, crash-safe install layout for portable (ZIP) itch.io builds:
 *
 * ```
 * <root>/
 *   versions/<versionId>/       complete extracted build trees
 *   metadata/<versionId>.json   delivery manifest + launch target (completion marker)
 *   staging/                    extraction/download scratch space
 *   install-state.json          atomic pointer to the active version
 *   update-journal.json         crash-recovery journal for a swap in progress
 * ```
 *
 * A version is activated by an atomic state-file write. The previous version is
 * kept so a failed or interrupted update always leaves a launchable install.
 */
class ItchInstallLayout(val root: File) {
    val versionsRoot: File get() = File(root, "versions")
    val metadataRoot: File get() = File(root, "metadata")
    val stagingRoot: File get() = File(root, "staging")
    val playerBackupsRoot: File get() = File(root, "player-file-backups")
    val stateFile: File get() = File(root, "install-state.json")
    val journalFile: File get() = File(root, "update-journal.json")

    fun versionDir(versionId: String): File = File(versionsRoot, versionId)
    fun metadataFile(versionId: String): File = File(metadataRoot, "$versionId.json")
    fun stagingDir(key: String): File = File(stagingRoot, key)

    fun ensureDirectories() {
        versionsRoot.mkdirs()
        metadataRoot.mkdirs()
        stagingRoot.mkdirs()
    }
}

object ItchInstaller {
    private const val METADATA_VERSION = 1

    fun layout(root: File): ItchInstallLayout = ItchInstallLayout(root)

    fun newVersionId(uploadId: Long, buildId: Long?, nowMs: Long = System.currentTimeMillis()): String =
        if (buildId != null) {
            "u${uploadId}_b$buildId"
        } else {
            "u${uploadId}_t$nowMs"
        }

    /**
     * Downloads are extracted through the project's hardened archive extractor
     * (zip-slip protection, entry/expanded-byte ceilings) into a scratch folder.
     */
    suspend fun extractArchive(archiveFile: File, destination: File): File =
        withContext(Dispatchers.IO) {
            if (!archiveFile.isFile) throw IOException("Downloaded archive is missing")
            ModArchiveExtractor.extract(archiveFile = archiveFile, destination = destination)
            resolveGameRoot(destination)
        }

    /**
     * A ZIP commonly nests the build in a single top-level folder (`MyGame/…`).
     * Descend while the folder contains exactly one visible directory and no files.
     */
    fun resolveGameRoot(extracted: File): File {
        var current = extracted
        repeat(3) {
            val children = current.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
            val directories = children.filter(File::isDirectory)
            val files = children.filter(File::isFile)
            if (files.isEmpty() && directories.size == 1 && File(current, ".itch.toml").isFile.not()) {
                current = directories.single()
            } else {
                return current
            }
        }
        return current
    }

    /**
     * Scans an extracted game folder and returns the delivery manifest that later
     * lets updates preserve player-created files (saves next to the EXE).
     */
    fun scanDelivery(gameRoot: File): ItchDeliveryManifest {
        if (!gameRoot.isDirectory) throw IOException("Game folder does not exist: ${gameRoot.absolutePath}")
        val rootPath = gameRoot.canonicalFile
        val files = gameRoot.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
                val relative = canonical.relativeTo(rootPath).invariantSeparatorsPath
                if (relative.startsWith("..")) return@mapNotNull null
                ItchDeliveryFile(path = relative, sizeBytes = canonical.length(), sha256 = sha256(canonical))
            }
            .sortedBy { it.path.lowercase() }
            .toList()
        return ItchDeliveryManifest(
            files = files,
            totalBytes = files.sumOf { it.sizeBytes },
        )
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds the Windows action declared by `.itch.toml`; otherwise falls back to
     * the executable candidates discovered in the build.
     */
    fun resolveLaunch(gameRoot: File): ItchLaunchResolution {
        ItchManifest.resolveLaunch(gameRoot)?.let { return ItchLaunchResolution.Selected(it) }
        val candidates = executableCandidates(gameRoot)
        return when (candidates.size) {
            0 -> ItchLaunchResolution.None
            1 -> ItchLaunchResolution.Selected(
                ItchLaunchSpec(
                    target = candidates.single().path,
                    fromManifest = false,
                    actionName = null,
                ),
            )
            else -> ItchLaunchResolution.NeedsSelection(candidates)
        }
    }

    fun executableCandidates(gameRoot: File): List<ItchExecutableCandidate> {
        if (!gameRoot.isDirectory) return emptyList()
        val rootPath = gameRoot.canonicalFile
        val rejected = listOf(
            "unins", "uninstall", "setup", "installer", "update", "updater", "patcher",
            "crashreport", "crashhandler", "dxsetup", "vcredist", "vc_redist", "dotnet",
        )
        return gameRoot.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.name.lowercase().endsWith(".exe") }
            .filterNot { file -> rejected.any { file.name.lowercase().contains(it) } }
            .map { file ->
                val path = file.canonicalFile.relativeTo(rootPath).invariantSeparatorsPath
                ItchExecutableCandidate(path = path, sizeBytes = file.length())
            }
            .sortedWith(compareBy({ depthRank(it.path) }, { it.path.lowercase() }))
            .distinctBy { it.path.lowercase() }
            .toList()
    }

    /**
     * Moves a fully extracted tree into `versions/<versionId>`, records its
     * delivery manifest and writes the completion metadata atomically.
     */
    suspend fun commitVersion(
        gameRoot: File,
        layout: ItchInstallLayout,
        meta: ItchVersionMeta,
    ): ItchVersionMeta = withContext(Dispatchers.IO) {
        layout.ensureDirectories()
        val target = layout.versionDir(meta.versionId)
        if (meta.launchTarget.isBlank()) throw IOException("The build has no launch target")
        val launchFile = File(gameRoot, meta.launchTarget)
        if (!launchFile.isFile) {
            throw IOException("Launch target is missing from the build: ${meta.launchTarget}")
        }

        if (target.exists() && !target.deleteRecursively()) {
            throw IOException("Could not clear the old version folder: ${target.absolutePath}")
        }
        target.parentFile?.mkdirs()
        if (!gameRoot.renameTo(target)) {
            gameRoot.copyRecursively(target = target, overwrite = true)
            gameRoot.deleteRecursively()
        }

        // Re-validate after the move so a truncated copy can never be activated.
        if (!File(target, meta.launchTarget).isFile) {
            target.deleteRecursively()
            throw IOException("The extracted build is incomplete: launch target is missing")
        }
        val manifest = scanDelivery(target)
        if (manifest.files.isEmpty()) {
            target.deleteRecursively()
            throw IOException("The extracted build is empty")
        }
        val completed = meta.copy(
            manifest = manifest,
            createdAtMs = System.currentTimeMillis(),
        )
        writeMeta(layout, completed)
        completed
    }

    /** Carries over new and modified files without overwriting the new build's files. */
    fun carryOverPlayerFiles(
        layout: ItchInstallLayout,
        oldMeta: ItchVersionMeta,
        newMeta: ItchVersionMeta,
    ): ItchPlayerCarryResult {
        val oldVersionDir = layout.versionDir(oldMeta.versionId)
        val newVersionDir = layout.versionDir(newMeta.versionId)
        if (!oldVersionDir.isDirectory || !newVersionDir.isDirectory) return ItchPlayerCarryResult()
        val delivered = oldMeta.manifest.files.associateBy { it.path }
        val rootPath = oldVersionDir.canonicalFile
        val conflictBackup by lazy {
            val backupRoot = layout.playerBackupsRoot.apply { mkdirs() }
            java.nio.file.Files.createTempDirectory(backupRoot.toPath(), "${oldMeta.versionId}-").toFile()
        }
        var copied = 0
        var conflicts = 0
        var copiedBytes = 0L
        oldVersionDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                val relative = canonical.relativeTo(rootPath).invariantSeparatorsPath
                if (relative.startsWith("..")) return@forEach
                val original = delivered[relative]
                if (original != null && original.sizeBytes == canonical.length() &&
                    (original.sha256 == null || original.sha256 == sha256(canonical))
                ) return@forEach
                // Never copy update bookkeeping if it ever lands inside the tree.
                if (relative == ItchManifest.fileName()) return@forEach
                val destination = File(newVersionDir, relative)
                val blockedParent = generateSequence(destination.parentFile) { it.parentFile }
                    .takeWhile { it != newVersionDir }.any { it.isFile }
                if (destination.exists() || blockedParent) {
                    // Pruning old versions must not remove the only copy of a conflicting save.
                    canonical.copyTo(File(conflictBackup, relative))
                    conflicts += 1
                    return@forEach
                }
                destination.parentFile?.mkdirs()
                canonical.copyTo(destination, overwrite = false)
                copied += 1
                copiedBytes += canonical.length()
            }
        return ItchPlayerCarryResult(copied = copied, conflicts = conflicts, copiedBytes = copiedBytes)
    }

    /**
     * Activates [meta] with an atomic state write. [beforeActivate] must repoint
     * the Wine container at the new executable *before* the state changes, so a
     * crash in between is recoverable: the journal plus the completed version
     * directory let [recover] finish the swap.
     */
    fun activate(
        layout: ItchInstallLayout,
        meta: ItchVersionMeta,
        nowMs: Long = System.currentTimeMillis(),
        beforeActivate: () -> Unit = {},
    ): ItchInstallState {
        layout.ensureDirectories()
        val previous = readState(layout)?.activeVersionId?.takeIf { it != meta.versionId }
        writeJournal(
            layout,
            ItchUpdateJournal(
                phase = ItchUpdatePhase.PREPARED,
                targetVersionId = meta.versionId,
                previousVersionId = previous,
                startedAtMs = nowMs,
            ),
        )
        beforeActivate()
        val state = ItchInstallState(
            activeVersionId = meta.versionId,
            previousVersionId = previous,
            launchTarget = meta.launchTarget,
            launchArgs = meta.launchArgs,
            installedAtMs = nowMs,
        )
        writeState(layout, state)
        clearJournal(layout)
        pruneVersions(layout, keep = setOfNotNull(meta.versionId, previous))
        return state
    }

    /** Changes the stored executable/args of the active version (user re-selection). */
    fun updateLaunchTarget(
        layout: ItchInstallLayout,
        versionId: String,
        target: String,
        args: List<String> = emptyList(),
    ): ItchInstallState {
        val meta = readMeta(layout, versionId)
            ?: throw IOException("Unknown itch.io version: $versionId")
        val versionDir = layout.versionDir(versionId)
        val file = File(versionDir, target)
        if (!file.isFile) throw IOException("Selected executable is missing: $target")
        val updated = meta.copy(launchTarget = target, launchArgs = args)
        writeMeta(layout, updated)
        val current = readState(layout)
        if (current?.activeVersionId == versionId) {
            val state = current.copy(launchTarget = target, launchArgs = args)
            writeState(layout, state)
            return state
        }
        return readState(layout) ?: throw IOException("No active itch.io install")
    }

    /**
     * Startup recovery for an interrupted update. Anything that was not fully
     * committed is discarded; a fully prepared version is activated; otherwise
     * the previous active version remains the one that launches.
     */
    fun recover(
        layout: ItchInstallLayout,
        onReactivate: (ItchVersionMeta) -> Unit = {},
    ): ItchRecoveryResult {
        if (!layout.root.exists()) return ItchRecoveryResult()
        layout.ensureDirectories()
        val discarded = mutableListOf<String>()

        // 1. Drop scratch data from interrupted downloads/extractions.
        layout.stagingRoot.listFiles()?.forEach { it.deleteRecursively() }

        // 2. Drop version folders without completion metadata, but never the version
        // the state file still points at (a corrupt metadata file must not delete a
        // working install).
        var state = readState(layout)
        val protectedVersions = setOfNotNull(state?.activeVersionId, state?.previousVersionId)
        val completedIds = listVersionIds(layout)
        layout.versionsRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory &&
                !completedIds.contains(dir.name) &&
                !protectedVersions.contains(dir.name)
            ) {
                dir.deleteRecursively()
                discarded += dir.name
            }
        }

        var recovered = false
        var message = ""

        val journal = readJournal(layout)
        if (journal != null) {
            val targetMeta = readMeta(layout, journal.targetVersionId)
            when {
                targetMeta == null -> {
                    if (!protectedVersions.contains(journal.targetVersionId)) {
                        layout.versionDir(journal.targetVersionId).deleteRecursively()
                        discarded += journal.targetVersionId
                    }
                    clearJournal(layout)
                    message = "Discarded an incomplete itch.io update"
                }
                state?.activeVersionId == targetMeta.versionId -> {
                    clearJournal(layout)
                }
                else -> {
                    val restored = runCatching {
                        onReactivate(targetMeta)
                        writeState(
                            layout,
                            ItchInstallState(
                                activeVersionId = targetMeta.versionId,
                                previousVersionId = journal.previousVersionId,
                                launchTarget = targetMeta.launchTarget,
                                launchArgs = targetMeta.launchArgs,
                                installedAtMs = journal.startedAtMs,
                            ),
                        )
                        clearJournal(layout)
                        recovered = true
                        message = "Completed an interrupted itch.io update"
                    }
                    if (restored.isFailure) {
                        // Keep the journal; the old version is still the active one.
                        message = "An interrupted itch.io update is still pending"
                    }
                    state = readState(layout)
                }
            }
        }

        // 3. Missing state file but complete versions on disk: point at the newest.
        if (state == null) {
            val newest = completedIds
                .mapNotNull { readMeta(layout, it) }
                .maxByOrNull { it.createdAtMs }
            if (newest != null) {
                runCatching {
                    onReactivate(newest)
                    val recoveredState = ItchInstallState(
                        activeVersionId = newest.versionId,
                        launchTarget = newest.launchTarget,
                        launchArgs = newest.launchArgs,
                        installedAtMs = newest.createdAtMs,
                    )
                    writeState(layout, recoveredState)
                    state = recoveredState
                    recovered = true
                    message = "Recovered the installed itch.io build"
                }
            }
        }

        state?.let {
            pruneVersions(layout, keep = setOfNotNull(it.activeVersionId, it.previousVersionId))
        }
        return ItchRecoveryResult(
            state = state,
            recovered = recovered,
            discardedVersionIds = discarded,
            message = message,
        )
    }

    fun verifyVersion(layout: ItchInstallLayout, meta: ItchVersionMeta): Boolean {
        val versionDir = layout.versionDir(meta.versionId)
        if (!versionDir.isDirectory) return false
        if (!File(versionDir, meta.launchTarget).isFile) return false
        if (meta.manifest.files.isEmpty()) return false
        // Every delivered file must still be present with its recorded size. Extra
        // files are expected: the game writes saves and settings next to the EXE.
        return meta.manifest.files.all { delivered ->
            val file = File(versionDir, delivered.path)
            file.isFile && file.length() == delivered.sizeBytes
        }
    }

    fun installedSize(layout: ItchInstallLayout, state: ItchInstallState?): Long {
        if (state == null) return 0L
        return readMeta(layout, state.activeVersionId)?.manifest?.totalBytes ?: 0L
    }

    fun readState(layout: ItchInstallLayout): ItchInstallState? = readStateFile(layout.stateFile)

    fun writeState(layout: ItchInstallLayout, state: ItchInstallState) {
        writeAtomic(layout.stateFile, ItchJson.encodeToString(ItchInstallState.serializer(), state))
    }

    fun readJournal(layout: ItchInstallLayout): ItchUpdateJournal? =
        readJson(layout.journalFile)?.let {
            runCatching { ItchJson.decodeFromString(ItchUpdateJournal.serializer(), it) }.getOrNull()
        }

    fun writeJournal(layout: ItchInstallLayout, journal: ItchUpdateJournal) {
        writeAtomic(layout.journalFile, ItchJson.encodeToString(ItchUpdateJournal.serializer(), journal))
    }

    fun clearJournal(layout: ItchInstallLayout) {
        layout.journalFile.delete()
    }

    fun readMeta(layout: ItchInstallLayout, versionId: String): ItchVersionMeta? {
        val raw = readJson(layout.metadataFile(versionId)) ?: return null
        val meta = runCatching {
            ItchJson.decodeFromString(ItchVersionMeta.serializer(), raw)
        }.getOrNull() ?: return null
        return if (meta.versionId == versionId) meta else null
    }

    fun writeMeta(layout: ItchInstallLayout, meta: ItchVersionMeta) {
        layout.ensureDirectories()
        writeAtomic(
            layout.metadataFile(meta.versionId),
            ItchJson.encodeToString(ItchVersionMeta.serializer(), meta),
        )
    }

    fun listVersionIds(layout: ItchInstallLayout): List<String> {
        val files = layout.metadataRoot.listFiles().orEmpty()
        return files.filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .filter { runCatching { readMeta(layout, it) }.getOrNull() != null }
    }

    fun deleteVersion(layout: ItchInstallLayout, versionId: String) {
        layout.versionDir(versionId).deleteRecursively()
        layout.metadataFile(versionId).delete()
    }

    fun pruneVersions(layout: ItchInstallLayout, keep: Set<String>) {
        // Legacy manifests only recorded sizes. Retain these versions because a same-size
        // edit cannot be distinguished from a delivered file during carry-over.
        val legacyVersions = listVersionIds(layout).filter { id ->
            readMeta(layout, id)?.manifest?.files?.any { it.sha256 == null } == true
        }.toSet()
        val retained = keep + legacyVersions
        layout.versionsRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && !retained.contains(dir.name)) dir.deleteRecursively()
        }
        layout.metadataRoot.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".json")
            if (file.isFile && file.name.endsWith(".json") && !retained.contains(id)) file.delete()
        }
    }

    /** Removes the whole install tree (uninstall). */
    fun uninstall(layout: ItchInstallLayout) {
        layout.root.deleteRecursively()
    }

    fun readManifest(layout: ItchInstallLayout, versionId: String): ItchDeliveryManifest? =
        readMeta(layout, versionId)?.manifest

    private fun readStateFile(file: File): ItchInstallState? =
        readJson(file)?.let {
            runCatching { ItchJson.decodeFromString(ItchInstallState.serializer(), it) }.getOrNull()
        }

    private fun readJson(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun depthRank(path: String): Int {
        val depth = path.count { it == '/' }
        return depth * 10 + if (path.contains("bin/", ignoreCase = true)) 1 else 0
    }
}

/** Result of resolving a launch target from a build tree. */
sealed interface ItchLaunchResolution {
    data class Selected(val spec: ItchLaunchSpec) : ItchLaunchResolution
    data class NeedsSelection(val candidates: List<ItchExecutableCandidate>) : ItchLaunchResolution
    data object None : ItchLaunchResolution
}

/** JSON codec for the launch argument list persisted in the database. */
object ItchArgCodec {
    private val serializer = kotlinx.serialization.builtins.ListSerializer(String.serializer())

    fun encode(args: List<String>): String =
        if (args.isEmpty()) "" else ItchJson.encodeToString(serializer, args)

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { ItchJson.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }

    /**
     * Formats launch arguments for a Windows command line. Arguments containing
     * whitespace or quotes are quoted, and embedded quotes are escaped, so an
     * `.itch.toml` action such as `args = ["--save-dir", "My Saves"]` survives.
     */
    fun toWindowsCommandLine(args: List<String>): String =
        args.joinToString(" ") { argument ->
            val needsQuotes = argument.isEmpty() || argument.startsWith('\'') || argument.endsWith('\\') ||
                argument.any { it.isWhitespace() || it == '"' }
            if (!needsQuotes) return@joinToString argument
            buildString {
                append('"')
                var backslashes = 0
                for (ch in argument) {
                    if (ch == '\\') {
                        backslashes++
                        continue
                    }
                    // Before a literal quote, double the backslashes and escape the quote itself.
                    repeat(if (ch == '"') 2 * backslashes + 1 else backslashes) { append('\\') }
                    backslashes = 0
                    append(ch)
                }
                repeat(2 * backslashes) { append('\\') }
                append('"')
            }
        }
}
