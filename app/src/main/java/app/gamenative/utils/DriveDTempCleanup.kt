package app.gamenative.utils

import android.os.Environment
import timber.log.Timber
import java.io.File

/**
 * Sweeps vcredist/DirectX self-extractor leftovers from the public Downloads
 * directory. Windows redistributable installers unpack into random hex-named
 * folders at the drive root; with drive D: mapped to Downloads those pile up
 * as empty (or installer-payload-only) directories after a game session.
 */
object DriveDTempCleanup {

    private val HEX_DIR_NAME = Regex("^[0-9a-fA-F]{16,32}$")
    private val INSTALLER_PAYLOAD_EXTENSIONS = setOf(
        "cab", "exe", "msi", "msu", "msp", "dll", "ini", "inf", "xml", "txt", "cat", "mst",
    )

    /** Deletes matching hex dirs that are empty or contain only installer payload. Returns deleted count. */
    fun sweep(downloadsDir: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)): Int {
        if (!downloadsDir.isDirectory) return 0
        var deleted = 0
        downloadsDir.listFiles().orEmpty()
            .filter { it.isDirectory && HEX_DIR_NAME.matches(it.name) }
            .forEach { dir ->
                val payloadOnly = dir.listFiles().orEmpty().all { child ->
                    if (child.isDirectory) {
                        child.listFiles().orEmpty().all { it.extension.lowercase() in INSTALLER_PAYLOAD_EXTENSIONS }
                    } else {
                        child.extension.lowercase() in INSTALLER_PAYLOAD_EXTENSIONS
                    }
                }
                if (payloadOnly) {
                    if (dir.deleteRecursively()) {
                        deleted++
                        Timber.d("Removed redist temp dir %s", dir.absolutePath)
                    } else {
                        Timber.w("Failed to remove redist temp dir %s", dir.absolutePath)
                    }
                }
            }
        return deleted
    }
}
