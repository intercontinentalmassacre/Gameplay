package app.gamenative.itch

import android.content.Context
import java.io.File

/**
 * Filesystem layout of the itch.io integration. Everything lives in app-private
 * storage; installed ZIP builds live inside the Wine prefix so the existing
 * "installed executable on drive C:" launch path can be reused unchanged.
 */
object ItchPaths {
    private const val ROOT = "itch"

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun credentialsFile(context: Context): File = File(root(context), "credentials.json")

    fun pendingAuthorizationFile(context: Context): File = File(root(context), "pending_oauth.json")

    fun downloadsDir(context: Context): File = File(root(context), "downloads")

    fun archiveFile(context: Context, gameId: Long, uploadId: Long, buildId: Long?): File {
        val suffix = buildId?.let { "b$it" } ?: "manual"
        return File(File(downloadsDir(context), gameId.toString()), "u${uploadId}_$suffix")
    }

    fun partialDownloadsDir(context: Context, gameId: Long): File =
        File(downloadsDir(context), gameId.toString())

    /** Folder mapped to drive A: while a Windows installer runs. */
    fun installerDir(context: Context, gameId: Long): File =
        File(File(root(context), "installers"), gameId.toString())

    /** Revision of the installer that is currently being run, consumed on completion. */
    fun pendingInstallerFile(context: Context, gameId: Long): File =
        File(root(context), "pending-installer-$gameId.json")

    /** Cheap check for the card label: an installer was downloaded and waits to run. */
    fun hasPendingInstaller(context: Context, gameId: Long): Boolean =
        pendingInstallerFile(context, gameId).isFile

    fun installRootFor(containerDriveC: File, gameId: Long): File =
        File(File(containerDriveC, "itch"), gameId.toString())

    /** Cheap check used from composition before any Hilt graph is touched. */
    fun hasCredentials(context: Context): Boolean {
        val file = credentialsFile(context)
        return file.isFile && file.length() > 0
    }
}
