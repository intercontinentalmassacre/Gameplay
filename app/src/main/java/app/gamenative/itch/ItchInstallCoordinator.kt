package app.gamenative.itch

import android.content.Context
import app.gamenative.data.ItchGame
import app.gamenative.localgames.InstallationSession
import app.gamenative.localgames.InstallationSessionStore
import app.gamenative.localgames.InstallationState
import app.gamenative.localgames.InstalledExecutableScanner
import app.gamenative.localgames.InstallerType
import app.gamenative.localgames.LocalContainerLaunch
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** Revision of a downloaded installer, consumed when the installer session completes. */
@Serializable
data class ItchPendingInstaller(
    val uploadId: Long? = null,
    val buildId: Long? = null,
    val userVersion: String? = null,
    val fileUpdatedAt: String? = null,
    val fileSize: Long = 0L,
    val filename: String = "",
    /** Launch target of the version that is still installed, restored on failure. */
    val previousLaunchTarget: String? = null,
    val previousLaunchArgs: String? = null,
) {
    fun toRevision(): ItchInstalledRevision = ItchInstalledRevision(
        kind = ItchInstallerKind.EXE,
        uploadId = uploadId,
        buildId = buildId,
        userVersion = userVersion,
        fileUpdatedAt = fileUpdatedAt,
        fileSize = fileSize,
        filename = filename,
    )
}

data class ItchRecoverySummary(
    val recoveredGameIds: List<Long> = emptyList(),
    val messages: List<String> = emptyList(),
)

/**
 * Bridges the pure [ItchInstaller] with the Android container/launch machinery.
 *
 * ZIP builds are extracted into `drive_c/itch/<gameId>/versions/<versionId>` and
 * launched through the existing "installed executable on C:" container mode.
 * EXE/MSI installers reuse the project's installation-session flow (drive A: +
 * baseline executable scan + candidate selection) but keep the itch.io app id,
 * so the library keeps a single card.
 */
@Singleton
class ItchInstallCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ItchLibraryRepository,
) {
    fun appIdOf(gameId: Long): String = "ITCH_$gameId"

    /** Installers downloaded and ready to run, used for the card's "Install" label. */
    private val preparedInstallers = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun hasPreparedInstaller(gameId: Long): Boolean = preparedInstallers.contains(gameId)

    fun markInstallerPrepared(gameId: Long) {
        preparedInstallers.add(gameId)
    }

    fun layout(gameId: Long): ItchInstallLayout {
        val container = ContainerUtils.getOrCreateContainer(context, appIdOf(gameId))
        return layoutForContainer(container, gameId)
    }

    /** Layout for an existing container only; never provisions one. */
    fun existingLayout(gameId: Long): ItchInstallLayout? = runCatching {
        layoutForContainer(ContainerUtils.getContainer(context, appIdOf(gameId)), gameId)
    }.getOrNull()

    private fun layoutForContainer(container: com.winlator.container.Container, gameId: Long): ItchInstallLayout {
        val driveC = File(container.rootDir, ".wine/drive_c")
        return ItchInstaller.layout(ItchPaths.installRootFor(driveC, gameId))
    }

    /** Relative-to-drive-C launch target of a version tree. */
    fun driveCRelativeTarget(gameId: Long, meta: ItchVersionMeta): String =
        "itch/$gameId/versions/${meta.versionId}/" + meta.launchTarget.trimStart('/', '\\')

    /** Points the game's container at the active version's executable. */
    fun repointContainer(gameId: Long, meta: ItchVersionMeta) {
        val container = ContainerUtils.getOrCreateContainer(context, appIdOf(gameId))
        val target = driveCRelativeTarget(gameId, meta)
        container.executablePath = target
        container.putExtra(LocalContainerLaunch.EXTRA_MODE, LocalContainerLaunch.MODE_INSTALLED_EXE_C)
        container.putExtra(LocalContainerLaunch.EXTRA_TARGET, target)
        container.putExtra(LocalContainerLaunch.EXTRA_LAUNCH_ARGS, ItchArgCodec.encode(meta.launchArgs))
        container.putExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID, null)
        container.saveData()
    }

    /**
     * Startup recovery: finishes or discards interrupted ZIP updates and
     * re-points containers at the version that is actually active. The previous
     * version is never deleted before a newer one is fully activated.
     */
    suspend fun recoverInterruptedInstalls(): ItchRecoverySummary = withContext(Dispatchers.IO) {
        val recovered = mutableListOf<Long>()
        val messages = mutableListOf<String>()
        runCatching { repository.installedGames() }
            .getOrDefault(emptyList())
            .filter { it.installKind == ItchGame.INSTALL_KIND_ZIP }
            .forEach { game ->
                runCatching {
                    val layout = existingLayout(game.gameId) ?: return@runCatching
                    val result = ItchInstaller.recover(layout) { meta ->
                        repointContainer(game.gameId, meta)
                    }
                    result.state?.let { state ->
                        ItchInstaller.readMeta(layout, state.activeVersionId)?.let { meta ->
                            repository.updateLaunchTarget(game.gameId, meta.launchTarget, meta.launchArgs)
                            repository.updateSizeOnDisk(game.gameId, meta.manifest.totalBytes)
                        }
                    }
                    if (result.recovered || result.discardedVersionIds.isNotEmpty()) {
                        recovered += game.gameId
                        messages += result.message
                    }
                }.onFailure { error ->
                    app.gamenative.PluviaApp.events.emitJava(
                        app.gamenative.events.AndroidEvent.LibraryInstallStatusChanged(
                            game.gameId.toInt(),
                            app.gamenative.data.GameSource.ITCH,
                        ),
                    )
                    messages += "Could not recover itch.io install ${game.gameId}: ${error.message}"
                }
            }
        ItchRecoverySummary(recoveredGameIds = recovered, messages = messages.filter(String::isNotBlank))
    }

    /**
     * Installs or updates a portable ZIP build.
     *
     * The new tree is downloaded, extracted and verified first; player files from
     * the previous version (saves kept next to the EXE) are carried over; only
     * then is the active-version pointer swapped. On any failure the previous
     * version remains active and launchable.
     */
    suspend fun installZip(
        gameId: Long,
        upload: ItchUpload,
        archive: File,
    ): Result<ItchVersionMeta> = withContext(Dispatchers.IO) {
        val layout = layout(gameId)
        val currentState = ItchInstaller.readState(layout)
        val versionId = ItchInstaller.newVersionId(upload.id, upload.build?.id)
            .let { candidate ->
                // Reinstalling a build must not overwrite an active or retained save tree.
                if (candidate == currentState?.activeVersionId || layout.versionDir(candidate).exists()) {
                    "${candidate}_r${System.currentTimeMillis()}"
                } else {
                    candidate
                }
            }
        val staging = layout.stagingDir(versionId)
        try {
            ItchInstaller.recover(layout) { meta -> repointContainer(gameId, meta) }

            val gameRoot = ItchInstaller.extractArchive(archive, staging)
            val spec = when (val resolution = ItchInstaller.resolveLaunch(gameRoot)) {
                is ItchLaunchResolution.Selected -> resolution.spec
                is ItchLaunchResolution.NeedsSelection -> {
                    // Several executables and no manifest: pick the highest-ranked one
                    // and let the player change it from the game card.
                    ItchLaunchSpec(target = resolution.candidates.first().path)
                }
                ItchLaunchResolution.None -> throw IOException(
                    "No Windows executable was found in this build",
                )
            }

            val meta = ItchInstaller.commitVersion(
                gameRoot = gameRoot,
                layout = layout,
                meta = ItchVersionMeta(
                    versionId = versionId,
                    uploadId = upload.id,
                    buildId = upload.build?.id,
                    userVersion = upload.build?.userVersion,
                    fileUpdatedAt = upload.updatedAt.takeIf(String::isNotBlank),
                    fileSizeBytes = upload.size,
                    launchTarget = spec.target,
                    launchArgs = spec.args,
                ),
            )

            val current = ItchInstaller.readState(layout)
            if (current != null && current.activeVersionId != meta.versionId) {
                val previousMeta = ItchInstaller.readMeta(layout, current.activeVersionId)
                if (previousMeta != null) {
                    ItchInstaller.carryOverPlayerFiles(
                        layout = layout,
                        oldMeta = previousMeta,
                        newMeta = meta,
                    )
                }
            }

            ItchInstaller.activate(layout, meta) { repointContainer(gameId, meta) }
            repository.markZipInstalled(
                gameId = gameId,
                upload = upload,
                installRoot = layout.root,
                launchTarget = meta.launchTarget,
                launchArgs = meta.launchArgs,
                sizeOnDisk = meta.manifest.totalBytes,
            )
            repository.recordError(gameId, null)
            archive.delete()
            staging.deleteRecursively()
            Result.success(meta)
        } catch (error: Exception) {
            // Never touch the active version on failure: only scratch data goes away.
            staging.deleteRecursively()
            repository.recordError(gameId, error.message ?: "Installation failed")
            Result.failure(error)
        }
    }

    /**
     * Prepares (or re-prepares) the Windows installer session for an EXE/MSI build.
     * The installer itself runs through the existing local-installer container
     * flow; Gameplay records the pending revision so completion keeps the same
     * upload identity.
     */
    suspend fun prepareInstaller(
        game: ItchGame,
        upload: ItchUpload,
        downloadedInstaller: File,
    ): Result<InstallationSession> = withContext(Dispatchers.IO) {
        runCatching {
            val gameId = game.gameId
            val appId = appIdOf(gameId)
            val directory = ItchPaths.installerDir(context, gameId)
            directory.mkdirs()
            val fileName = sanitizeInstallerName(downloadedInstaller.name)
            val managedInstaller = File(directory, fileName)
            if (downloadedInstaller.absolutePath != managedInstaller.absolutePath) {
                downloadedInstaller.copyTo(managedInstaller, overwrite = true)
                downloadedInstaller.delete()
            }

            val container = ContainerUtils.getOrCreateContainer(context, appId)
            val driveC = File(container.rootDir, ".wine/drive_c")
            val store = InstallationSessionStore(context)
            val now = System.currentTimeMillis()

            var session = InstallationSession(
                id = UUID.randomUUID().toString(),
                title = game.title,
                sourceUri = "itch://${game.gameId}",
                sourceName = managedInstaller.name,
                installerType = InstallerType.EXE,
                managedInstallerPath = managedInstaller.absolutePath,
                installerRelativePath = fileName,
                state = InstallationState.READY_TO_LAUNCH,
                appId = appId,
                containerId = container.id,
                baselineExecutablePaths = InstalledExecutableScanner.findCandidates(driveC),
                createdAt = now,
                updatedAt = now,
            )
            store.save(session)
            session = session.transitionTo(InstallationState.INSTALLER_RUNNING)
            store.save(session)

            armInstallerContainer(session)
            writePendingInstaller(gameId, upload, game)
            preparedInstallers.add(gameId)
            session
        }
    }

    /**
     * Points the container back at the installer. Used when preparing a new
     * installer and when the user retries a failed installer session (after a
     * failed update the container was restored to the installed game).
     */
    fun armInstallerContainer(session: InstallationSession) {
        val appId = session.appId ?: return
        val container = ContainerUtils.getOrCreateContainer(context, appId)
        val fileName = session.installerRelativePath
        container.executablePath = fileName
        container.putExtra(LocalContainerLaunch.EXTRA_MODE, LocalContainerLaunch.MODE_INSTALLER_EXE_A)
        container.putExtra(LocalContainerLaunch.EXTRA_TARGET, fileName)
        container.putExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID, session.id)
        container.putExtra(LocalContainerLaunch.EXTRA_LAUNCH_ARGS, null)
        container.saveData()
    }

    /** True when the game has a prepared installer session waiting to be launched. */
    suspend fun installerSession(gameId: Long): InstallationSession? = withContext(Dispatchers.IO) {
        val appId = appIdOf(gameId)
        InstallationSessionStore(context).loadAll().firstOrNull { session ->
            session.appId == appId &&
                session.state == InstallationState.INSTALLER_RUNNING
        }
    }

    /**
     * Called after the installer session completed and an executable was chosen.
     * Records the new revision and clears the pending marker.
     */
    suspend fun completeInstallerSession(session: InstallationSession): Boolean =
        withContext(Dispatchers.IO) {
            val appId = session.appId ?: return@withContext false
            val gameId = appId.removePrefix("ITCH_").toLongOrNull() ?: return@withContext false
            val target = session.selectedExecutablePath?.takeIf(String::isNotBlank)
                ?: return@withContext false
            val pending = readPendingInstaller(gameId)
            repository.markInstallerInstalled(
                gameId = gameId,
                revision = pending?.toRevision(),
                installRoot = ItchPaths.installerDir(context, gameId),
                launchTarget = target,
                launchArgs = emptyList(),
            )
            clearPendingInstaller(gameId)
            preparedInstallers.remove(gameId)
            // The installer payload is no longer needed once the game is installed.
            runCatching { session.managedInstallerPath.takeIf(String::isNotBlank)?.let(::File)?.delete() }
            true
        }

    /** Potential executables of the active version, for "change executable" on the card. */
    suspend fun executableCandidates(gameId: Long): List<ItchExecutableCandidate> =
        withContext(Dispatchers.IO) {
            val layout = existingLayout(gameId) ?: return@withContext emptyList()
            val state = ItchInstaller.readState(layout) ?: return@withContext emptyList()
            ItchInstaller.executableCandidates(layout.versionDir(state.activeVersionId))
        }

    /** Remembers a different executable for an installed ZIP build. */
    suspend fun changeLaunchTarget(gameId: Long, target: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val layout = existingLayout(gameId)
                    ?: throw IOException("This itch.io game has no container")
                val state = ItchInstaller.readState(layout)
                    ?: throw IOException("This itch.io game is not installed")
                val meta = ItchInstaller.readMeta(layout, state.activeVersionId)
                    ?: throw IOException("Installed version metadata is missing")
                ItchInstaller.updateLaunchTarget(layout, state.activeVersionId, target, meta.launchArgs)
                repointContainer(gameId, meta.copy(launchTarget = target))
                repository.updateLaunchTarget(gameId, target, meta.launchArgs)
            }
        }

    /** Removes a ZIP install; EXE installs keep the installer-managed Wine prefix. */
    suspend fun uninstall(gameId: Long) = withContext(Dispatchers.IO) {
        runCatching {
            val game = repository.getGame(gameId)
            if (game?.installKind == ItchGame.INSTALL_KIND_ZIP) {
                existingLayout(gameId)?.let(ItchInstaller::uninstall)
                runCatching {
                    val container = ContainerUtils.getContainer(context, appIdOf(gameId))
                    container.executablePath = ""
                    container.putExtra(LocalContainerLaunch.EXTRA_MODE, null)
                    container.putExtra(LocalContainerLaunch.EXTRA_TARGET, null)
                    container.putExtra(LocalContainerLaunch.EXTRA_LAUNCH_ARGS, null)
                    container.saveData()
                }
            }
            ItchPaths.installerDir(context, gameId).deleteRecursively()
            clearPendingInstaller(gameId)
            preparedInstallers.remove(gameId)
            repository.markUninstalled(gameId)
        }
    }

    private fun writePendingInstaller(gameId: Long, upload: ItchUpload, game: ItchGame) {
        val pending = ItchPendingInstaller(
            uploadId = upload.id,
            buildId = upload.build?.id,
            userVersion = upload.build?.userVersion,
            fileUpdatedAt = upload.updatedAt.takeIf(String::isNotBlank),
            fileSize = upload.size,
            filename = upload.filename,
            previousLaunchTarget = game.launchTarget.takeIf { game.isInstalled && it.isNotBlank() },
            previousLaunchArgs = game.launchArgs.takeIf { game.isInstalled && it.isNotBlank() },
        )
        val file = ItchPaths.pendingInstallerFile(context, gameId)
        file.parentFile?.mkdirs()
        file.writeText(ItchJson.encodeToString(ItchPendingInstaller.serializer(), pending))
    }

    /**
     * Restores the container's previous launch target after a failed or
     * interrupted installer update, so the installed version stays launchable.
     */
    suspend fun restorePreviousLaunch(gameId: Long): Boolean = withContext(Dispatchers.IO) {
        val pending = readPendingInstaller(gameId) ?: return@withContext false
        val target = pending.previousLaunchTarget?.takeIf(String::isNotBlank)
            ?: return@withContext false
        runCatching {
            val container = ContainerUtils.getContainer(context, appIdOf(gameId))
            container.executablePath = target
            container.putExtra(LocalContainerLaunch.EXTRA_MODE, LocalContainerLaunch.MODE_INSTALLED_EXE_C)
            container.putExtra(LocalContainerLaunch.EXTRA_TARGET, target)
            container.putExtra(
                LocalContainerLaunch.EXTRA_LAUNCH_ARGS,
                pending.previousLaunchArgs.orEmpty(),
            )
            container.putExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID, null)
            container.saveData()
            true
        }.getOrDefault(false)
    }

    private fun readPendingInstaller(gameId: Long): ItchPendingInstaller? {
        val file = ItchPaths.pendingInstallerFile(context, gameId)
        if (!file.isFile) return null
        return runCatching {
            ItchJson.decodeFromString(ItchPendingInstaller.serializer(), file.readText())
        }.getOrNull()
    }

    private fun clearPendingInstaller(gameId: Long) {
        ItchPaths.pendingInstallerFile(context, gameId).delete()
    }

    private fun sanitizeInstallerName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[<>:"/\\|?*\x00-\x1F]"""), "_")
            .take(80)
        if (cleaned.isBlank()) return "setup.exe"
        return if (cleaned.lowercase().endsWith(".exe") || cleaned.lowercase().endsWith(".msi")) {
            cleaned
        } else {
            "$cleaned.exe"
        }
    }
}
