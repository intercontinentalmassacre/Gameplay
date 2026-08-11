package app.gamenative.localgames

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.gamenative.PrefManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object LocalInstallerImporter {
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private const val MAX_NAME_LENGTH = 80
    private val invalidNameCharacters = Regex("""[<>:"/\\|?*\x00-\x1F]""")

    sealed interface ImportResult {
        data class Ready(val session: InstallationSession) : ImportResult
        data class ReadyPortable(val folderName: String) : ImportResult
        data class Rejected(val reason: String) : ImportResult
        data class Failed(
            val reason: String,
            val cause: Throwable,
            val session: InstallationSession? = null,
        ) : ImportResult
    }

    suspend fun importInstaller(context: Context, sourceUri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val sourceName = queryDisplayName(context, sourceUri)
                ?: return@withContext ImportResult.Rejected("The selected installer has no usable name")
            val installerType = when {
                sourceName.endsWith(".exe", ignoreCase = true) -> InstallerType.EXE
                sourceName.endsWith(".msi", ignoreCase = true) -> InstallerType.MSI
                sourceName.endsWith(".iso", ignoreCase = true) -> {
                    // Disc images follow the mounted-media workflow, not the executable one.
                    return@withContext LocalDiscImageImporter.importDiscImage(context, sourceUri, sourceName)
                }
                else -> return@withContext ImportResult.Rejected("Choose a Windows EXE or MSI installer, or an ISO disc image")
            }

            val destinationFolder = createDestinationFolder(sourceName)
                ?: return@withContext ImportResult.Failed(
                    reason = "Gameplay could not create an installer workspace",
                    cause = IOException("Could not allocate a unique CustomGames folder"),
                )
            val installerName = sanitizeFileName(sourceName)
            val destinationInstaller = File(destinationFolder, installerName)

            try {
                copySource(context, sourceUri, destinationInstaller)
            } catch (error: CancellationException) {
                destinationFolder.deleteRecursively()
                throw error
            } catch (error: Exception) {
                destinationFolder.deleteRecursively()
                return@withContext ImportResult.Failed("Gameplay could not copy the installer", error)
            }

            stageInstallerSession(
                context = context,
                sourceUriString = sourceUri.toString(),
                sourceName = sourceName,
                destinationFolder = destinationFolder,
                installerFile = destinationInstaller,
                installerRelativePath = installerName,
                installerType = installerType,
                cleanupOnFailure = true,
            )
        }

    /**
     * Shared post-staging flow: inspects the installer, creates the installation
     * session, and prepares the dedicated container in installer launch mode.
     * Used by both direct EXE/MSI imports and disc-image extraction.
     */
    internal suspend fun stageInstallerSession(
        context: Context,
        sourceUriString: String,
        sourceName: String,
        destinationFolder: File,
        installerFile: File,
        installerRelativePath: String,
        installerType: InstallerType,
        cleanupOnFailure: Boolean,
    ): ImportResult {
        val inspection = runCatching {
            FileInputStream(installerFile).use(WindowsExecutableInspector::inspect)
        }.getOrElse { error ->
            if (cleanupOnFailure) destinationFolder.deleteRecursively()
            return ImportResult.Failed("Gameplay could not inspect the installer", error)
        }
        val rejection = validateInstaller(installerType, inspection)
        if (rejection != null) {
            if (cleanupOnFailure) destinationFolder.deleteRecursively()
            return ImportResult.Rejected(rejection)
        }

        val store = InstallationSessionStore(context)
        val now = System.currentTimeMillis()
        var session = InstallationSession(
            id = UUID.randomUUID().toString(),
            title = destinationFolder.name.removeSuffix(" setup").removeSuffix(" disc"),
            sourceUri = sourceUriString,
            sourceName = sourceName,
            installerType = installerType,
            managedInstallerPath = installerFile.absolutePath,
            installerRelativePath = installerRelativePath,
            state = InstallationState.SOURCE_STAGED,
            createdAt = now,
            updatedAt = now,
        )

        try {
            store.save(session)
            session = session.transitionTo(InstallationState.CONTAINER_CREATING)
            store.save(session)

            val folderPath = destinationFolder.canonicalPath
            if (folderPath !in PrefManager.customGameManualFolders) {
                PrefManager.customGameManualFolders = PrefManager.customGameManualFolders + folderPath
            }
            CustomGameScanner.invalidateCache()

            val libraryItem = CustomGameScanner.createLibraryItemFromFolder(
                folderPath = folderPath,
                allowSteamAssociation = false,
            ) ?: throw IOException("Could not register installer workspace as a local game")
            CustomGameScanner.invalidateCache()

            val container = ContainerUtils.getOrCreateContainer(context, libraryItem.appId)
            val launchMode = when (installerType) {
                InstallerType.EXE -> LocalContainerLaunch.MODE_INSTALLER_EXE_A
                InstallerType.MSI -> LocalContainerLaunch.MODE_INSTALLER_MSI_A
            }
            container.executablePath = installerRelativePath
            container.putExtra(LocalContainerLaunch.EXTRA_MODE, launchMode)
            container.putExtra(LocalContainerLaunch.EXTRA_TARGET, installerRelativePath)
            container.putExtra(LocalContainerLaunch.EXTRA_INSTALLATION_SESSION_ID, session.id)
            container.saveData()

            session = session.copy(
                appId = libraryItem.appId,
                containerId = container.id,
            ).transitionTo(InstallationState.READY_TO_LAUNCH)
            store.save(session)
            return ImportResult.Ready(session)
        } catch (error: Exception) {
            val failed = runCatching {
                session.transitionTo(
                    next = InstallationState.FAILED,
                    error = error.message ?: "Container preparation failed",
                )
            }.getOrElse {
                session.copy(
                    state = InstallationState.FAILED,
                    previousState = session.state,
                    updatedAt = System.currentTimeMillis(),
                    lastError = error.message,
                )
            }
            runCatching { store.save(failed) }
            return ImportResult.Failed(
                reason = "Gameplay could not prepare the installer container",
                cause = error,
                session = failed,
            )
        }
    }

    internal suspend fun copySource(context: Context, sourceUri: Uri, destination: File) {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } ?: throw IOException("The selected installer cannot be read")
    }

    private fun validateInstaller(type: InstallerType, inspection: ExecutableInspection): String? =
        when (type) {
            InstallerType.MSI -> if (inspection.kind == ExecutableKind.WINDOWS_INSTALLER_MSI) {
                null
            } else {
                "The selected MSI file has an invalid installer signature"
            }
            InstallerType.EXE -> when (inspection.kind) {
                ExecutableKind.WINDOWS_32_PE,
                ExecutableKind.WINDOWS_64_PE,
                -> null
                ExecutableKind.WINDOWS_16_NE -> "Win16 installers require the legacy runtime"
                ExecutableKind.DOS_ONLY -> "DOS installers are outside this Windows workflow"
                ExecutableKind.MALFORMED -> inspection.reason ?: "The installer header is malformed"
                else -> "The selected file is not a supported Windows installer"
            }
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val providerName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return providerName?.trim()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf(String::isNotBlank)
    }

    internal fun createDestinationFolder(sourceName: String, suffix: String = " setup"): File? {
        val root = File(CustomGameScanner.defaultRootPath)
        val rawBase = sourceName.substringBeforeLast('.')
            .replace(Regex("(?i)^(setup|install|installer)[._ -]*"), "")
            .ifBlank { "New game" }
        val base = sanitizeFolderName(rawBase)
        for (index in 1..100) {
            val candidateSuffix = if (index == 1) suffix else "$suffix ($index)"
            val candidate = File(root, base + candidateSuffix)
            if (!candidate.exists() && candidate.mkdirs()) return candidate
        }
        return null
    }

    private fun sanitizeFolderName(value: String): String = value
        .replace(invalidNameCharacters, "_")
        .trim('.', ' ')
        .take(MAX_NAME_LENGTH)
        .ifBlank { "New game" }

    private fun sanitizeFileName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(invalidNameCharacters, "_")
        .takeIf(String::isNotBlank)
        ?: "setup.exe"
}
