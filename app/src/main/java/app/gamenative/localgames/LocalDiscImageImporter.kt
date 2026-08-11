package app.gamenative.localgames

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import timber.log.Timber

/**
 * Mounted-media workflow for ISO disc images. An ISO is never treated as an
 * executable: it is extracted into a dedicated workspace, then routed to the
 * installer flow (when a setup program is found) or registered as a portable
 * game folder.
 */
object LocalDiscImageImporter {
    private const val READ_BLOCK_SIZE = 1024 * 1024
    private const val MAX_ENTRIES = 20000
    private const val MAX_EXPANDED_BYTES = 40L * 1024 * 1024 * 1024 // 40 GB
    private const val MAX_RELATIVE_PATH_LENGTH = 240
    private const val MAX_SCAN_DEPTH = 4

    private val preferredInstallerNames = listOf(
        "setup.exe",
        "install.exe",
        "autorun.exe",
        "setup.msi",
        "install.msi",
    )

    suspend fun importDiscImage(
        context: Context,
        sourceUri: Uri,
        sourceName: String,
    ): LocalInstallerImporter.ImportResult = withContext(Dispatchers.IO) {
        val destinationFolder = LocalInstallerImporter.createDestinationFolder(sourceName, suffix = " disc")
            ?: return@withContext LocalInstallerImporter.ImportResult.Failed(
                reason = "Gameplay could not create a disc-image workspace",
                cause = IOException("Could not allocate a unique CustomGames folder"),
            )

        val isoFile = File(destinationFolder, "disc.iso")
        try {
            LocalInstallerImporter.copySource(context, sourceUri, isoFile)
        } catch (error: CancellationException) {
            destinationFolder.deleteRecursively()
            throw error
        } catch (error: Exception) {
            destinationFolder.deleteRecursively()
            return@withContext LocalInstallerImporter.ImportResult.Failed(
                "Gameplay could not copy the disc image",
                error,
            )
        }

        try {
            extractIso9660(isoFile, destinationFolder)
        } catch (error: CancellationException) {
            destinationFolder.deleteRecursively()
            throw error
        } catch (error: Exception) {
            destinationFolder.deleteRecursively()
            return@withContext LocalInstallerImporter.ImportResult.Failed(
                "Gameplay could not read the disc image (only ISO9660 discs are supported)",
                error,
            )
        } finally {
            // The extracted contents are the workspace from here on.
            isoFile.delete()
        }

        // Mounted-media routing: an installer on the disc takes priority.
        val installerCandidate = findInstallerCandidate(destinationFolder)
        if (installerCandidate != null) {
            val relativePath = installerCandidate.relativeTo(destinationFolder).invariantSeparatorsPath
            val isMsi = installerCandidate.name.endsWith(".msi", ignoreCase = true)
            return@withContext LocalInstallerImporter.stageInstallerSession(
                context = context,
                sourceUriString = sourceUri.toString(),
                sourceName = sourceName,
                destinationFolder = destinationFolder,
                installerFile = installerCandidate,
                installerRelativePath = relativePath,
                installerType = if (isMsi) InstallerType.MSI else InstallerType.EXE,
                cleanupOnFailure = false,
            )
        }

        // Otherwise register the extracted folder as a portable game.
        val portableExe = findPortableExecutable(destinationFolder)
        if (portableExe != null) {
            val folderPath = destinationFolder.canonicalPath
            if (folderPath !in PrefManager.customGameManualFolders) {
                PrefManager.customGameManualFolders = PrefManager.customGameManualFolders + folderPath
            }
            CustomGameScanner.invalidateCache()
            return@withContext LocalInstallerImporter.ImportResult.ReadyPortable(destinationFolder.name)
        }

        destinationFolder.deleteRecursively()
        LocalInstallerImporter.ImportResult.Rejected(
            "The disc image contains no installer and no game executable",
        )
    }

    /** Prefers well-known installer names at shallow depth, then any root-level installer exe/msi. */
    private fun findInstallerCandidate(root: File): File? {
        val executables = collectExecutables(root)
        val byPreferredName = preferredInstallerNames.firstNotNullOfOrNull { preferred ->
            executables.firstOrNull { it.name.equals(preferred, ignoreCase = true) }
        }
        if (byPreferredName != null) return byPreferredName
        return executables.firstOrNull { file ->
            val name = file.nameWithoutExtension.lowercase()
            name.contains("setup") || name.contains("install") || name.contains("autorun")
        }
    }

    private fun findPortableExecutable(root: File): File? {
        return CustomGameScanner.findUniqueExeRelativeToFolder(root)?.let { relative ->
            File(root, relative.replace('/', File.separatorChar)).takeIf { it.isFile }
        }
    }

    private fun collectExecutables(root: File): List<File> {
        val result = mutableListOf<File>()
        root.walkTopDown()
            .maxDepth(MAX_SCAN_DEPTH)
            .filter { it.isFile }
            .filter {
                it.name.endsWith(".exe", ignoreCase = true) ||
                    it.name.endsWith(".msi", ignoreCase = true)
            }
            .sortedWith(compareBy({ it.relativeTo(root).invariantSeparatorsPath.count { c -> c == '/' } }, { it.name.lowercase() }))
            .toCollection(result)
        return result
    }

    private suspend fun extractIso9660(isoFile: File, destination: File) {
        val archive = try {
            Archive.readNew()
        } catch (e: LinkageError) {
            throw IOException("Disc-image extraction is not available on this device", e)
        }

        var expandedBytes = 0L
        var entries = 0
        try {
            Archive.setCharset(archive, Charsets.UTF_8.name().toByteArray(Charsets.UTF_8))
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatIso9660(archive)
            Archive.readOpenFileName(
                archive,
                isoFile.absolutePath.toByteArray(Charsets.UTF_8),
                READ_BLOCK_SIZE.toLong(),
            )

            val buffer = ByteArray(READ_BLOCK_SIZE)
            while (true) {
                val entry = try {
                    Archive.readNextHeader(archive)
                } catch (e: ArchiveException) {
                    if (e.code == Archive.ERRNO_EOF) break else throw e
                }
                if (entry == 0L) break
                if (++entries > MAX_ENTRIES) throw IOException("The disc image has too many entries")

                val entryName = archiveEntryName(entry)
                val outFile = safeDestination(destination, entryName)
                when (ArchiveEntry.filetype(entry)) {
                    ArchiveEntry.AE_IFDIR -> outFile.mkdirs()
                    ArchiveEntry.AE_IFREG, 0 -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).buffered(READ_BLOCK_SIZE).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val readBuffer = java.nio.ByteBuffer.allocateDirect(READ_BLOCK_SIZE)
                                val read = try {
                                    Archive.readData(archive, readBuffer)
                                    readBuffer.position()
                                } catch (e: ArchiveException) {
                                    if (e.code == Archive.ERRNO_EOF) 0 else throw e
                                }
                                if (read <= 0) break
                                expandedBytes += read
                                if (expandedBytes > MAX_EXPANDED_BYTES) {
                                    throw IOException("The disc image expands beyond the safety limit")
                                }
                                readBuffer.flip()
                                readBuffer.get(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    else -> {
                        // Skip symlinks, devices, and other non-regular entries silently.
                        Timber.d("Skipping unsupported disc entry type: $entryName")
                    }
                }
            }
        } catch (e: ArchiveException) {
            throw IOException("The disc image could not be read: ${e.message.orEmpty().ifBlank { "unknown error" }}", e)
        } finally {
            runCatching { Archive.readClose(archive) }
            runCatching { Archive.readFree(archive) }
        }

        if (entries == 0) throw IOException("The disc image is empty or not an ISO9660 disc")
    }

    private fun archiveEntryName(entry: Long): String {
        val utf8Name = ArchiveEntry.pathnameUtf8(entry)
        if (!utf8Name.isNullOrBlank()) return utf8Name
        val rawName = ArchiveEntry.pathname(entry)
        if (rawName != null && rawName.isNotEmpty()) return rawName.toString(Charsets.UTF_8)
        throw IOException("The disc image contains an entry without a path")
    }

    private fun safeDestination(destination: File, rawName: String): File {
        val normalized = rawName.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")
        if (
            normalized.isBlank() ||
            normalized.length > MAX_RELATIVE_PATH_LENGTH ||
            normalized.split('/').any { it == ".." } ||
            rawName.trim().startsWith("/") ||
            Regex("^[A-Za-z]:.*").containsMatchIn(rawName.trim())
        ) {
            throw IOException("Unsafe path inside the disc image: $rawName")
        }
        val outFile = File(destination, normalized).canonicalFile
        val destCanonical = destination.canonicalFile
        if (!outFile.path.startsWith(destCanonical.path + File.separator) && outFile != destCanonical) {
            throw IOException("Disc entry escapes extraction directory: $rawName")
        }
        return outFile
    }
}
