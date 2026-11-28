package org.buran.runtime

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal object WineMono {
    // Version and SHA-256 digest embedded in the bundled Box64 Wine appwiz.cpl.
    val box64 = Package(
        "9.4.0", 84_639_232, "cf6173ae94b79e9de13d9a74cdb2560a886fc3d271f9489acb1cfdbd961cacb2"
    )

    internal class Package internal constructor(val version: String, private val size: Long, private val sha256: String) {
        val fileName get() = "wine-mono-$version-x86.msi"

        private fun directory(context: Context) = context.buranFilesDir.resolve("components/wine-mono/$version")

        fun cached(context: Context): File? =
            directory(context).resolve(fileName).takeIf { it.isFile && it.length() == size }

        // The marker is visible to the separate Gamescope process.
        fun enabled(context: Context): File? =
            cached(context)?.takeIf { directory(context).resolve("enabled").isFile }

        fun setEnabled(context: Context, enabled: Boolean) {
            val marker = directory(context).resolve("enabled").toPath()
            if (enabled) {
                checkNotNull(cached(context)) { "Сначала загрузите или импортируйте $fileName" }
                try {
                    Files.createFile(marker)
                } catch (_: FileAlreadyExistsException) {
                    // Already enabled.
                }
            } else {
                Files.deleteIfExists(marker)
            }
        }

        fun download(context: Context) {
            val url = URI.create("https://dl.winehq.org/wine/wine-mono/$version/$fileName").toURL()
            val connection = url.openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            val deadline = SystemClock.elapsedRealtime() + 180_000
            try {
                check(connection.responseCode == 200) { "WineHQ вернул HTTP ${connection.responseCode}" }
                val length = connection.contentLengthLong
                check(length == -1L || length == size) { "Неожиданный размер файла WineHQ" }
                connection.inputStream.use { input -> store(context, input, deadline) }
            } finally {
                connection.disconnect()
            }
        }

        fun importMsi(context: Context, input: InputStream) = store(context, input)

        private fun store(context: Context, input: InputStream, deadline: Long = Long.MAX_VALUE) {
            val directory = Files.createDirectories(directory(context).toPath())
            val staged = Files.createTempFile(directory, ".wine-mono-", ".tmp")
            try {
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                Files.newOutputStream(staged).use { output ->
                    while (true) {
                        check(SystemClock.elapsedRealtime() < deadline) { "Время загрузки Wine Mono истекло" }
                        val count = input.read(buffer)
                        if (count == -1) break
                        bytes += count
                        check(bytes <= size) { "Файл больше официального $fileName" }
                        output.write(buffer, 0, count)
                    }
                }
                val runner = File(context.applicationInfo.nativeLibraryDir, "libburan.so")
                val process = ProcessBuilder(
                    runner.path, "publish-component", staged.toString(),
                    directory.resolve(fileName).toString(), size.toString(), sha256
                ).redirectErrorStream(true).start()
                try {
                    check(process.waitFor(30, TimeUnit.SECONDS)) { "Время проверки Wine Mono истекло" }
                    check(process.exitValue() == 0) { "Проверка или сохранение $fileName не удались" }
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                    process.inputStream.close()
                    process.outputStream.close()
                }
            } finally {
                Files.deleteIfExists(staged)
            }
        }
    }
}
