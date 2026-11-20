package app.gamenative.audio

import android.content.Context
import app.gamenative.utils.AssetUtils
import com.winlator.container.Container
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** In-process AAudio output through bionic libasound. */
object GameNativeAudio {
    private const val ARCHIVE = "gamenative-audio-guest.tzst"
    private const val PLUGIN = "libasound_module_pcm_gamenative.so"
    private const val CONFIG = "gamenative.conf"
    private const val PAYLOAD_STAMP = ".gnaudio-payload-sha256"

    fun isSupported(variant: String): Boolean = variant.equals(Container.BIONIC, ignoreCase = true)

    fun configPath(imageFs: ImageFs): String =
        "${imageFs.rootDir.path}/usr/share/alsa/alsa.conf:${imageFs.rootDir.path}/usr/share/alsa/$CONFIG"

    @Synchronized
    fun install(context: Context, imageFs: ImageFs) {
        val targetPlugin = File(imageFs.libDir, "alsa-lib/$PLUGIN")
        val targetConfig = File(imageFs.rootDir, "usr/share/alsa/$CONFIG")
        val stamp = File(imageFs.configDir, PAYLOAD_STAMP)
        val payload = context.assets.open(ARCHIVE).use { it.readBytes() }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val installedVersionMatches = runCatching { stamp.readText() }.getOrNull() == fingerprint
        if (installedVersionMatches && isInstalled(targetPlugin) && isInstalled(targetConfig)) return

        val staging = Files.createTempDirectory(imageFs.rootDir.toPath(), ".gnaudio-").toFile()
        try {
            check(TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, payload.inputStream(), staging)) {
                "Could not extract the GNAudio runtime"
            }
            val plugin = File(staging, "alsa-lib/$PLUGIN")
            val config = File(staging, CONFIG)
            check(isInstalled(plugin) && isInstalled(config)) {
                "The GNAudio runtime is incomplete"
            }
            if (!installedVersionMatches || !isInstalled(targetPlugin)) installFile(plugin, targetPlugin)
            if (!installedVersionMatches || !isInstalled(targetConfig)) installFile(config, targetConfig)

            // Only advertise this asset after both files are in place. A crash during an upgrade
            // leaves the old stamp, so the next launch repairs the pair before starting Wine.
            val stagedStamp = File(staging, PAYLOAD_STAMP).apply { writeText(fingerprint) }
            installFile(stagedStamp, stamp)
        } finally {
            AssetUtils.deleteTree(staging)
        }
    }

    private fun isInstalled(file: File): Boolean = file.isFile && file.length() > 0

    private fun installFile(source: File, target: File) {
        Files.createDirectories(target.parentFile!!.toPath())
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
