package org.buran.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.IOException

/** Controls the guest Vulkan layer using its upstream abstract Unix socket protocol. */
internal class MangoHudOverlay(sessionId: String) : AutoCloseable {
    val socketName = "buran-mango-$sessionId"
    private var socket: LocalSocket? = null
    private val greeting = StringBuilder()
    private val buffer = ByteArray(512)
    private var peerPid = -1
    private var connectedAt = 0L
    private var nextProbeAt = 0L
    private val attempted = mutableSetOf<Int>()
    private var ready = false
    private var enabled = false
    val mayBeEnabled: Boolean get() = enabled

    // Called on the session worker. Returns whether the guest layer is currently responsive.
    fun update(desired: Boolean, candidates: () -> List<Int>): Boolean {
        try {
            val connection = socket ?: run {
                val now = SystemClock.elapsedRealtime()
                if (now < nextProbeAt) return false
                nextProbeAt = now + 5_000L
                connect(candidates()) ?: return false
            }
            val descriptor = StructPollfd().apply {
                fd = connection.fileDescriptor
                events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
            }
            if (Os.poll(arrayOf(descriptor), 0) > 0) {
                val count = connection.inputStream.read(buffer)
                if (count < 1) throw IOException("MangoHud disconnected")
                if (!ready) {
                    greeting.append(String(buffer, 0, count, Charsets.UTF_8))
                    ready = greeting.contains(":MangoHudControlVersion=1;")
                    if (greeting.length > 1024 && !ready) throw IOException("Invalid MangoHud greeting")
                }
            }
            if (ready && enabled != desired) {
                connection.outputStream.write(":hud;".toByteArray(Charsets.UTF_8))
                enabled = desired
            }
            if (!ready && SystemClock.elapsedRealtime() - connectedAt > 2_000L) close()
            return ready
        } catch (_: IOException) {
            close()
        } catch (_: ErrnoException) {
            close()
        }
        return false
    }

    private fun connect(pids: List<Int>): LocalSocket? {
        if (peerPid !in pids) enabled = false
        attempted.retainAll(pids.toSet())
        val pid = if (enabled) peerPid else {
            pids.firstOrNull { it !in attempted } ?: pids.firstOrNull()?.also { attempted.clear() } ?: return null
        }
        attempted.add(pid)
        return LocalSocket().also { connection ->
            socket = connection
            connection.connect(LocalSocketAddress("$socketName-$pid", LocalSocketAddress.Namespace.ABSTRACT))
            if (connection.peerCredentials.pid != pid) throw IOException("Unexpected MangoHud socket owner")
            if (pid != peerPid) enabled = false
            peerPid = pid
            connectedAt = SystemClock.elapsedRealtime()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        ready = false
        greeting.clear()
    }

    companion object {
        const val CONTROL = "BURAN_MANGOHUD_CONTROL"
        private const val LAYER = "VK_LAYER_MANGOHUD_overlay_aarch64"

        fun configure(root: File, display: Map<String, String>, env: MutableMap<String, String>) {
            val control = display[CONTROL] ?: return
            if (!root.resolve("usr/share/vulkan/implicit_layer.d/MangoHud.aarch64.json").isFile) return
            env["VK_LOADER_LAYERS_ALLOW"] = LAYER
            env["MANGOHUD"] = "1"
            env["MANGOHUD_CONFIG"] =
                "no_display=1,control=$control-%p,toggle_hud=VoidSymbol,toggle_preset=VoidSymbol," +
                "reload_cfg=VoidSymbol,fps,frametime,frame_timing,cpu_stats,gpu_stats"
        }
    }
}
