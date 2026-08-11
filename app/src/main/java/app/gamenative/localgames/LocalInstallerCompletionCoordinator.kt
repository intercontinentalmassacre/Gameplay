package app.gamenative.localgames

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalInstallerCompletionCoordinator {
    sealed interface Result {
        data object NotAnInstallerSession : Result
        data class Completed(val session: InstallationSession) : Result
        data class NeedsExecutableSelection(val session: InstallationSession) : Result
        data class Failed(val session: InstallationSession, val reason: String) : Result
    }

    suspend fun handleInstallerExit(context: Context, appId: String): Result = withContext(Dispatchers.IO) {
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull()
            ?: return@withContext Result.NotAnInstallerSession
        val sessionId = container.getExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID)
            .takeIf(String::isNotBlank)
            ?: return@withContext Result.NotAnInstallerSession
        val store = InstallationSessionStore(context)
        val session = store.load(sessionId) ?: return@withContext Result.NotAnInstallerSession

        if (container.getExtra(LocalContainerLaunch.EXTRA_MODE) == LocalContainerLaunch.MODE_INSTALLED_EXE_C) {
            val target = container.getExtra(LocalContainerLaunch.EXTRA_TARGET)
            return@withContext completePersistedContainer(store, session, target)
        }

        val awaiting = when (session.state) {
            InstallationState.INSTALLER_RUNNING -> session.transitionTo(InstallationState.AWAITING_RESULT)
            InstallationState.AWAITING_RESULT,
            InstallationState.CANDIDATE_SELECTION,
            -> session
            InstallationState.COMPLETED -> return@withContext Result.Completed(session)
            else -> return@withContext Result.Failed(session, "Installer session is not awaiting a result")
        }
        store.save(awaiting)

        val baseline = awaiting.baselineExecutablePaths.map(String::lowercase).toSet()
        val candidates = InstalledExecutableScanner.findCandidates(File(container.rootDir, ".wine/drive_c"))
            .filterNot { it.lowercase() in baseline }
        if (candidates.isEmpty()) {
            val failed = awaiting.transitionTo(
                InstallationState.FAILED,
                error = "The installer exited but no launchable game executable was found on drive C:",
            )
            store.save(failed)
            return@withContext Result.Failed(failed, requireNotNull(failed.lastError))
        }

        val selecting = if (awaiting.state == InstallationState.CANDIDATE_SELECTION) {
            awaiting.copy(candidateExecutablePaths = candidates, updatedAt = System.currentTimeMillis())
        } else {
            awaiting.copy(candidateExecutablePaths = candidates)
                .transitionTo(InstallationState.CANDIDATE_SELECTION)
        }
        store.save(selecting)

        if (candidates.size == 1) {
            finalizeSelection(store, selecting, container, candidates.single())
        } else {
            Result.NeedsExecutableSelection(selecting)
        }
    }

    /** Marks a session interrupted by process death without pretending installer exited cleanly. */
    suspend fun markInterrupted(context: Context, sessionId: String): Result = withContext(Dispatchers.IO) {
        val store = InstallationSessionStore(context)
        val session = store.load(sessionId)
            ?: return@withContext Result.Failed(
                missingSession(sessionId),
                "Installation session was not found",
            )
        if (session.state == InstallationState.FAILED) {
            return@withContext Result.Failed(session, session.lastError ?: "Installation was interrupted")
        }
        if (session.state !in setOf(
                InstallationState.INSTALLER_RUNNING,
                InstallationState.PAUSED,
                InstallationState.RESTART_REQUIRED,
            )
        ) {
            return@withContext Result.Failed(session, "Installation cannot be recovered from ${session.state}")
        }

        val failed = session.transitionTo(
            InstallationState.FAILED,
            error = "Installation was interrupted. Run the installer again to continue.",
        )
        store.save(failed)
        Result.Failed(failed, requireNotNull(failed.lastError))
    }

    suspend fun selectExecutable(context: Context, sessionId: String, relativePath: String): Result =
        withContext(Dispatchers.IO) {
            val store = InstallationSessionStore(context)
            val session = requireNotNull(store.load(sessionId)) { "Installation session was not found" }
            require(session.state == InstallationState.CANDIDATE_SELECTION) {
                "Installation session is not waiting for executable selection"
            }
            require(relativePath in session.candidateExecutablePaths) { "Executable is not a known candidate" }
            val appId = requireNotNull(session.appId) { "Installation session has no app id" }
            val container = ContainerUtils.getContainer(context, appId)
            finalizeSelection(store, session, container, relativePath)
        }

    /**
     * Recovery path after a failed or unsatisfying discovery: scans drive C:
     * without the uninstaller/updater name filter and re-enters candidate
     * selection so the user can point at the game executable manually.
     */
    suspend fun browseAllExecutables(context: Context, sessionId: String): Result = withContext(Dispatchers.IO) {
        val store = InstallationSessionStore(context)
        val session = store.load(sessionId)
            ?: return@withContext Result.Failed(
                InstallationSession(
                    id = sessionId,
                    title = "Installation",
                    sourceUri = "",
                    sourceName = "",
                    installerType = InstallerType.EXE,
                    managedInstallerPath = "",
                    installerRelativePath = "",
                    state = InstallationState.FAILED,
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
                "Installation session was not found",
            )
        if (session.state !in setOf(
                InstallationState.FAILED,
                InstallationState.AWAITING_RESULT,
                InstallationState.CANDIDATE_SELECTION,
            )
        ) {
            return@withContext Result.Failed(session, "Installation cannot be browsed in its current state")
        }
        val appId = session.appId
            ?: return@withContext Result.Failed(session, "Installation session has no app id")
        val container = runCatching { ContainerUtils.getContainer(context, appId) }.getOrNull()
            ?: return@withContext Result.Failed(session, "Installation container was not found")

        val baseline = session.baselineExecutablePaths.map(String::lowercase).toSet()
        val executables = InstalledExecutableScanner.findAllExecutables(File(container.rootDir, ".wine/drive_c"))
            .filterNot { it.lowercase() in baseline }
        if (executables.isEmpty()) {
            return@withContext Result.Failed(session, "No executables found on drive C:")
        }

        val base = if (session.state == InstallationState.CANDIDATE_SELECTION) {
            session
        } else if (session.state == InstallationState.FAILED) {
            session.copy(lastError = null).transitionTo(InstallationState.CANDIDATE_SELECTION)
        } else {
            session.transitionTo(InstallationState.CANDIDATE_SELECTION)
        }
        val selecting = base.copy(
            candidateExecutablePaths = executables,
            updatedAt = System.currentTimeMillis(),
        )
        store.save(selecting)
        Result.NeedsExecutableSelection(selecting)
    }

    /**
     * Recovery path for a failed installation: re-snapshots the executable
     * baseline, marks the session as running again, and returns the app id so
     * the caller can relaunch the installer in its existing container.
     */
    suspend fun retryInstaller(context: Context, sessionId: String): String = withContext(Dispatchers.IO) {
        val store = InstallationSessionStore(context)
        val session = requireNotNull(store.load(sessionId)) { "Installation session was not found" }
        require(
            session.state == InstallationState.FAILED ||
                session.state == InstallationState.READY_TO_LAUNCH
        ) { "Installation cannot be restarted in its current state" }
        val appId = requireNotNull(session.appId) { "Installation session has no app id" }
        val container = ContainerUtils.getContainer(context, appId)

        val baseline = InstalledExecutableScanner.findCandidates(File(container.rootDir, ".wine/drive_c"))
        val ready = if (session.state == InstallationState.FAILED) {
            session.transitionTo(InstallationState.READY_TO_LAUNCH)
        } else {
            session
        }
        store.save(
            ready.copy(
                baselineExecutablePaths = baseline,
                lastError = null,
            ).transitionTo(InstallationState.INSTALLER_RUNNING),
        )
        appId
    }

    private fun finalizeSelection(
        store: InstallationSessionStore,
        session: InstallationSession,
        container: Container,
        relativePath: String,
    ): Result {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        val driveC = File(container.rootDir, ".wine/drive_c").canonicalFile
        val executable = File(driveC, normalized).canonicalFile
        if (!executable.isFile || !executable.path.startsWith(driveC.path + File.separator)) {
            val failed = session.transitionTo(
                InstallationState.FAILED,
                error = "Selected executable is missing or outside drive C: $normalized",
            )
            store.save(failed)
            return Result.Failed(failed, requireNotNull(failed.lastError))
        }

        container.executablePath = normalized
        container.putExtra(LocalContainerLaunch.EXTRA_MODE, LocalContainerLaunch.MODE_INSTALLED_EXE_C)
        container.putExtra(LocalContainerLaunch.EXTRA_TARGET, normalized)
        container.putExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID, null)
        container.saveData()

        val completed = session.copy(
            selectedExecutablePath = normalized,
            candidateExecutablePaths = session.candidateExecutablePaths.ifEmpty { listOf(normalized) },
        ).transitionTo(InstallationState.COMPLETED)
        store.save(completed)
        return Result.Completed(completed)
    }

    private fun completePersistedContainer(
        store: InstallationSessionStore,
        session: InstallationSession,
        target: String,
    ): Result {
        if (session.state == InstallationState.COMPLETED) return Result.Completed(session)
        if (target.isBlank()) return Result.Failed(session, "Installed container has no launch target")
        val selecting = when (session.state) {
            InstallationState.INSTALLER_RUNNING -> session
                .transitionTo(InstallationState.AWAITING_RESULT)
                .transitionTo(InstallationState.CANDIDATE_SELECTION)
            InstallationState.AWAITING_RESULT -> session.transitionTo(InstallationState.CANDIDATE_SELECTION)
            InstallationState.CANDIDATE_SELECTION -> session
            else -> return Result.Failed(session, "Installation state cannot be recovered")
        }
        val completed = selecting.copy(
            selectedExecutablePath = target,
            candidateExecutablePaths = selecting.candidateExecutablePaths.ifEmpty { listOf(target) },
        ).transitionTo(InstallationState.COMPLETED)
        store.save(completed)
        return Result.Completed(completed)
    }

    private fun missingSession(sessionId: String) = InstallationSession(
        id = sessionId,
        title = "Installation",
        sourceUri = "",
        sourceName = "",
        installerType = InstallerType.EXE,
        managedInstallerPath = "",
        installerRelativePath = "",
        state = InstallationState.FAILED,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
