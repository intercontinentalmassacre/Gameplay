package app.gamenative.localgames

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Imports one portable Windows executable selected through Android's Storage Access Framework.
 *
 * A SAF URI cannot be mounted as the container's A: drive, so the selected file is copied into
 * the app-managed CustomGames directory first. The copied game then follows the same custom-game
 * and container path as games already stored there.
 */
object LocalGameImporter {
    private const val COPY_BUFFER_SIZE = 256 * 1024
    private const val MAX_GAME_NAME_LENGTH = 80
    private val likelyInstallerName = Regex("^(setup|install|installer|unins)([._ -].*)?$", RegexOption.IGNORE_CASE)
    private val invalidFolderCharacters = Regex("""[<>:"/\\|?*\x00-\x1F]""")

    sealed interface ImportResult {
        data class Ready(
            val appId: String,
            val title: String,
            val executablePath: String,
        ) : ImportResult

        data class Rejected(val reason: String) : ImportResult

        data class Failed(val reason: String, val cause: Throwable) : ImportResult
    }

    /**
     * Creates a self-contained copy of [sourceUri] and prepares it as a portable Custom Game.
     * This deliberately does not run the file while importing it.
     */
    suspend fun importPortableExecutable(context: Context, sourceUri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val sourceName = queryDisplayName(context, sourceUri)
                ?: return@withContext ImportResult.Rejected("The selected file has no usable name")

            if (!sourceName.endsWith(".exe", ignoreCase = true)) {
                return@withContext ImportResult.Rejected("Choose a Windows .exe file to import as a portable game")
            }
            if (likelyInstallerName.matches(sourceName.substringBeforeLast('.'))) {
                return@withContext ImportResult.Rejected(
                    "This looks like an installer. Installer containers are not available yet.",
                )
            }

            val destinationFolder = createDestinationFolder(sourceName)
                ?: return@withContext ImportResult.Failed(
                    "Gameplay could not create an import folder",
                    IOException("Could not create a unique CustomGames destination"),
                )
            val destinationExecutable = File(destinationFolder, sanitizeFileName(sourceName))

            try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destinationExecutable).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                } ?: return@withContext cleanupAndReject(
                    destinationFolder,
                    "Gameplay could not read the selected file",
                )

                when (
                    val prepared = CustomGameContainerCoordinator.preparePortableGame(
                        context = context,
                        gameFolder = destinationFolder,
                        executable = destinationExecutable,
                    )
                ) {
                    is CustomGameContainerCoordinator.PreparationResult.Ready -> ImportResult.Ready(
                        appId = prepared.appId,
                        title = prepared.title,
                        executablePath = prepared.executablePath,
                    )

                    is CustomGameContainerCoordinator.PreparationResult.Rejected ->
                        cleanupAndReject(destinationFolder, prepared.reason)

                    is CustomGameContainerCoordinator.PreparationResult.Failed ->
                        cleanupAndFail(destinationFolder, prepared.reason, prepared.cause)
                }
            } catch (error: CancellationException) {
                cleanupDestination(destinationFolder)
                throw error
            } catch (error: Exception) {
                cleanupAndFail(destinationFolder, "Gameplay could not import the selected file", error)
            }
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val fromProvider = runCatching {
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
        return fromProvider?.trim()?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf(String::isNotBlank)
    }

    private fun createDestinationFolder(sourceName: String): File? {
        val root = File(CustomGameScanner.defaultRootPath)
        val baseName = sanitizeFolderName(sourceName.substringBeforeLast('.'))
        for (index in 1..100) {
            val suffix = if (index == 1) "" else " ($index)"
            val candidate = File(root, baseName + suffix)
            if (!candidate.exists() && candidate.mkdirs()) return candidate
        }
        return null
    }

    private fun sanitizeFolderName(value: String): String = value
        .replace(invalidFolderCharacters, "_")
        .trim('.', ' ')
        .take(MAX_GAME_NAME_LENGTH)
        .ifBlank { "Imported game" }

    private fun sanitizeFileName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(invalidFolderCharacters, "_")
        .takeIf(String::isNotBlank)
        ?: "game.exe"

    private fun cleanupAndReject(destination: File, reason: String): ImportResult {
        cleanupDestination(destination)
        return ImportResult.Rejected(reason)
    }

    private fun cleanupAndFail(destination: File, reason: String, cause: Throwable): ImportResult {
        cleanupDestination(destination)
        return ImportResult.Failed(reason, cause)
    }

    /** Only deletes a directory created by [createDestinationFolder] for this import attempt. */
    private fun cleanupDestination(destination: File) {
        runCatching { destination.deleteRecursively() }
    }
}
