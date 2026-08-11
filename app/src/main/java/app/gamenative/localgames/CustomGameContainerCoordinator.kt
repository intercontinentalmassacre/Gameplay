package app.gamenative.localgames

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.io.FileInputStream

/**
 * Bridges a locally selected Windows executable to GameNative's existing Custom Game and
 * container infrastructure. It intentionally does not use Steam matching or Steam app IDs.
 */
object CustomGameContainerCoordinator {
    sealed interface PreparationResult {
        data class Ready(
            val appId: String,
            val title: String,
            val executablePath: String,
            val executableKind: ExecutableKind,
        ) : PreparationResult

        data class Rejected(
            val reason: String,
            val inspection: ExecutableInspection? = null,
        ) : PreparationResult

        data class Failed(
            val reason: String,
            val cause: Throwable,
        ) : PreparationResult
    }

    /**
     * Registers [gameFolder] as a Custom Game, creates/reuses its standard GameNative container,
     * and sets [executable] as the explicit launch target.
     *
     * Installer packages and legacy Win16 binaries are intentionally rejected here. They need
     * their dedicated, persisted installation/runtime flows rather than a portable-game launch.
     */
    fun preparePortableGame(
        context: Context,
        gameFolder: File,
        executable: File,
    ): PreparationResult {
        val root = runCatching { gameFolder.canonicalFile }.getOrElse {
            return PreparationResult.Failed("Unable to resolve the game folder", it)
        }
        val target = runCatching { executable.canonicalFile }.getOrElse {
            return PreparationResult.Failed("Unable to resolve the selected executable", it)
        }

        if (!root.isDirectory) {
            return PreparationResult.Rejected("The selected game folder does not exist")
        }
        if (!target.isFile) {
            return PreparationResult.Rejected("The selected executable does not exist")
        }

        val rootPrefix = root.path.trimEnd(File.separatorChar) + File.separator
        if (!target.path.startsWith(rootPrefix)) {
            return PreparationResult.Rejected("The executable must be inside the selected game folder")
        }

        val inspection = runCatching {
            FileInputStream(target).use(WindowsExecutableInspector::inspect)
        }.getOrElse {
            return PreparationResult.Failed("Unable to read the selected executable", it)
        }
        if (inspection.kind !in portableGameKinds) {
            return PreparationResult.Rejected(
                reason = unsupportedPortableGameReason(inspection),
                inspection = inspection,
            )
        }

        val executablePath = target.path.removePrefix(rootPrefix).replace(File.separatorChar, '/')
        val manualFolders = PrefManager.customGameManualFolders
        val wasAlreadyRegistered = root.path in manualFolders

        if (!wasAlreadyRegistered) {
            PrefManager.customGameManualFolders = manualFolders + root.path
            CustomGameScanner.invalidateCache()
        }

        return try {
            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(
                folderPath = root.path,
                allowSteamAssociation = false,
            ) ?: return rollbackRegistrationIfNeeded(
                root = root,
                wasAlreadyRegistered = wasAlreadyRegistered,
                reason = "The selected folder could not be registered as a local game",
            )

            val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
            container.executablePath = executablePath
            container.saveData()

            PreparationResult.Ready(
                appId = libraryItem.appId,
                title = libraryItem.name,
                executablePath = executablePath,
                executableKind = inspection.kind,
            )
        } catch (exception: Exception) {
            rollbackRegistrationIfNeeded(
                root = root,
                wasAlreadyRegistered = wasAlreadyRegistered,
                reason = "Gameplay could not create a container for the selected game",
                cause = exception,
            )
        }
    }

    private fun rollbackRegistrationIfNeeded(
        root: File,
        wasAlreadyRegistered: Boolean,
        reason: String,
        cause: Throwable? = null,
    ): PreparationResult {
        if (!wasAlreadyRegistered) {
            PrefManager.customGameManualFolders = PrefManager.customGameManualFolders - root.path
            CustomGameScanner.invalidateCache()
        }
        return if (cause == null) {
            PreparationResult.Rejected(reason)
        } else {
            PreparationResult.Failed(reason, cause)
        }
    }

    private fun unsupportedPortableGameReason(inspection: ExecutableInspection): String = when (inspection.kind) {
        ExecutableKind.WINDOWS_INSTALLER_MSI -> "Select this file through the installer workflow"
        ExecutableKind.WINDOWS_16_NE -> "Win16 games require the legacy Windows runtime"
        ExecutableKind.DOS_ONLY -> "DOS games are outside this Windows-only import workflow"
        ExecutableKind.MALFORMED -> inspection.reason ?: "The executable header is malformed"
        ExecutableKind.UNKNOWN -> inspection.reason ?: "The selected file is not a supported Windows executable"
        ExecutableKind.WINDOWS_32_PE,
        ExecutableKind.WINDOWS_64_PE,
        -> error("Supported executable kind cannot be rejected")
    }

    private val portableGameKinds = setOf(
        ExecutableKind.WINDOWS_32_PE,
        ExecutableKind.WINDOWS_64_PE,
    )
}
