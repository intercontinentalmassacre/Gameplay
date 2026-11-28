package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.utils.AssetUtils
import com.winlator.container.Container
import com.winlator.xenvironment.components.PulseAudioComponent
import java.io.Closeable
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

internal object BuranPulseAudio {
    fun start(context: Context, intent: Intent, container: Container?): Closeable? {
        val runtime = intent.getBundleExtra("steamBionicRuntime") ?: return null
        if ((container?.audioDriver ?: PrefManager.audioDriver) != "pulseaudio") return null
        AssetUtils.preparePulseAudio(context.assets, File(context.filesDir, "pulseaudio"))
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "bp-").toFile()
        val socket = File(directory, "ps").path
        var server: Process? = null
        val closed = AtomicBoolean()
        val lease = Closeable {
            if (closed.compareAndSet(false, true)) {
                try {
                    server?.destroyForcibly()
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
        try {
            val lowLatency = container?.pulseaudioLowLatency ?: PrefManager.pulseaudioLowLatency
            val process = PulseAudioComponent.startServer(context, directory, socket, lowLatency, 1f, 1)
            server = process
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!canConnect(socket)) {
                check(process.isAlive && SystemClock.elapsedRealtime() < deadline) {
                    context.getString(R.string.buran_pulseaudio_unavailable)
                }
                Thread.sleep(50)
            }
            val environment = runtime.getStringArrayList("environment").orEmpty()
                .filterNot { it.startsWith("PULSE_SERVER=") }
            runtime.putStringArrayList("environment", ArrayList(environment + "PULSE_SERVER=unix:$socket"))
            intent.putExtra("steamBionicRuntime", runtime)
            return lease
        } catch (error: Throwable) {
            runCatching { lease.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun canConnect(path: String): Boolean = try {
        LocalSocket().use { it.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM)) }
        true
    } catch (_: IOException) {
        false
    }
}
