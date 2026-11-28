package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import app.gamenative.R
import com.winlator.sysvshm.SysVSHMConnectionHandler
import com.winlator.sysvshm.SysVSHMRequestHandler
import com.winlator.sysvshm.SysVSharedMemory
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xconnector.XConnectorEpoll
import com.winlator.xenvironment.ImageFs
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/** Hosts the Bionic guest's SysV shared-memory compatibility protocol for one Wine session. */
internal object BuranSysVShm {
    fun start(context: Context, intent: Intent): Closeable? {
        val runtime = intent.getBundleExtra("steamBionicRuntime") ?: return null
        val library = ImageFs.find(context).libDir.resolve("libandroid-sysvshm.so")
        check(library.isFile && library.canRead()) {
            context.getString(R.string.buran_proton_shm_unavailable)
        }
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "bshm-").toFile()
        val memory = SysVSharedMemory()
        var connector: XConnectorEpoll? = null
        val closed = AtomicBoolean()
        val lease = Closeable {
            if (closed.compareAndSet(false, true)) {
                try {
                    connector?.stop()
                } finally {
                    try {
                        memory.deleteAll()
                    } finally {
                        directory.deleteRecursively()
                    }
                }
            }
        }
        try {
            val socket = UnixSocketConfig.createSocket(directory.path, UnixSocketConfig.SYSVSHM_SERVER_PATH)
            val server = XConnectorEpoll(socket, SysVSHMConnectionHandler(memory), SysVSHMRequestHandler())
            connector = server
            server.start()
            val environment = runtime.getStringArrayList("environment").orEmpty()
                .associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
            environment["ANDROID_SYSVSHM_SERVER"] = socket.path
            val preload = listOf(library.path) + environment["LD_PRELOAD"].orEmpty().split(':').filter(String::isNotBlank)
            environment["LD_PRELOAD"] = preload.distinct().joinToString(":")
            runtime.putStringArrayList("environment", ArrayList(environment.map { "${it.key}=${it.value}" }))
            intent.putExtra("steamBionicRuntime", runtime)
            return lease
        } catch (error: Throwable) {
            runCatching { lease.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
