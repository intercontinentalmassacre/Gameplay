package app.gamenative.utils

import android.content.res.AssetManager
import com.winlator.core.TarCompressorUtils
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

object AssetUtils {
    private const val PULSE_ARCHIVE = "pulseaudio-gamenative-20260612.tzst"
    private const val PULSE_STAMP = ".payload-sha256"
    private val pulseFiles = listOf(
        "modules/libprotocol-native.so",
        "modules/module-aaudio-sink.so",
        "modules/module-native-protocol-unix.so",
        "pactl",
    )
    fun log() : Timber.Tree {
        return Timber.tag("AssetUtils")
    }

    /** Delete an extracted payload without following symbolic links. */
    internal fun deleteTree(file: File) {
        val path = file.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            file.setWritable(true, true)
            file.setExecutable(true, true)
            file.listFiles()?.forEach(::deleteTree)
        }
        Files.deleteIfExists(path)
    }

    fun preparePulseAudio(assetManager: AssetManager, targetDir: File) {
        val success = try {
            preparePulseAudio(targetDir, { assetManager.open(PULSE_ARCHIVE) }) { staging ->
                TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, assetManager, PULSE_ARCHIVE, staging)
            }
        } catch (error: IOException) {
            log().e(error, "Could not install PulseAudio")
            return
        }
        if (!success) log().e("Could not install PulseAudio")
    }

    @Synchronized
    internal fun preparePulseAudio(
        targetDir: File,
        openArchive: () -> InputStream,
        extract: (File) -> Boolean,
    ): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val archiveHash = openArchive().use { sha256(it, buffer) }
        val stamp = File(targetDir, PULSE_STAMP)
        if (Files.isRegularFile(stamp.toPath(), LinkOption.NOFOLLOW_LINKS) && stamp.length() == archiveHash.size.toLong()) {
            val current = pulseFingerprint(targetDir, archiveHash, buffer)
            if (current != null && stamp.readBytes().contentEquals(current)) return true
        }

        val staging = Files.createTempDirectory(targetDir.parentFile!!.toPath(), ".pulseaudio-").toFile()
        try {
            if (!extract(staging)) return false
            val fingerprint = pulseFingerprint(staging, archiveHash, buffer) ?: return false
            File(staging, PULSE_STAMP).writeBytes(fingerprint)
            deleteTree(targetDir)
            return staging.renameTo(targetDir)
        } finally {
            deleteTree(staging)
        }
    }

    private fun pulseFingerprint(directory: File, archiveHash: ByteArray, buffer: ByteArray): ByteArray? {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(archiveHash)
        for (relativePath in pulseFiles) {
            val file = File(directory, relativePath)
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() <= 0 || !file.canExecute()) return null
            digest.update(file.inputStream().use { sha256(it, buffer) })
        }
        return digest.digest()
    }

    private fun sha256(input: InputStream, buffer: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return digest.digest()
            digest.update(buffer, 0, count)
        }
    }
}
