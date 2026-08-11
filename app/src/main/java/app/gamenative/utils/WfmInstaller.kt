package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Installs the minimally patched original file manager used by Open Container.
 *
 * Only WFM's copy operation bypasses Wine's crashing SHFileOperation implementation. Keep this
 * payload separate from the container pattern so app updates can repair existing containers
 * without rebuilding ImageFS or deleting user data.
 */
object WfmInstaller {
    private const val ASSET_DIRECTORY = "wfm"
    private val payloads = listOf(
        Payload(
            filename = "wfm.exe",
            sha256 = "a7bb48aa14c59ece23c011c43c8439869267b13387d4a972f462a06575deebbb",
        ),
    )

    fun install(context: Context, container: Container): Boolean {
        val windowsDirectory = File(container.rootDir, ".wine/drive_c/windows")
        if (!windowsDirectory.isDirectory && !windowsDirectory.mkdirs()) {
            Timber.e("Could not create WFM destination: %s", windowsDirectory)
            return false
        }

        return payloads.all { payload ->
            installPayload(context, windowsDirectory, payload)
        }
    }

    private fun installPayload(
        context: Context,
        destinationDirectory: File,
        payload: Payload,
    ): Boolean {
        val destination = File(destinationDirectory, payload.filename)
        if (destination.isFile &&
            runCatching { sha256(destination) }.getOrNull() == payload.sha256
        ) {
            return true
        }

        val temporary = File(destinationDirectory, ".${payload.filename}.installing")
        return try {
            Files.deleteIfExists(temporary.toPath())
            context.assets.open("$ASSET_DIRECTORY/${payload.filename}").use { input ->
                Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            check(sha256(temporary) == payload.sha256) {
                "Bundled ${payload.filename} failed integrity verification"
            }

            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            FileUtils.chmod(destination, 493)
            Timber.i("Installed minimally patched %s", payload.filename)
            true
        } catch (error: Exception) {
            Timber.e(error, "Failed to install %s", payload.filename)
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            false
        }
    }

    private fun sha256(file: File): String =
        file.inputStream().buffered().use(::sha256)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class Payload(
        val filename: String,
        val sha256: String,
    )
}
