package com.winlator.core

import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Best-effort reclaim on Android kernels exposing the per-process reclaim interface. */
class PausedGameMemory {
    val sessionId: String = UUID.randomUUID().toString()
    private var pending: Future<*>? = null

    @Synchronized
    fun schedule() {
        if (pending != null) return
        pending = worker.schedule({
            val count = reclaim(File("/proc"), Process.myUid(), sessionId)
            Log.d("PausedGameMemory", "Reclaimed paused game processes: $count")
        }, 1, TimeUnit.SECONDS)
    }

    @Synchronized
    fun cancel() {
        pending?.cancel(true)
        pending = null
    }

    companion object {
        const val ENV_KEY = "GAMEPLAY_RECLAIM_SESSION"
        fun isSupported(): Boolean = File("/proc/self/reclaim").canWrite()
        private val worker = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "game-memory-reclaim").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }

        internal fun reclaim(proc: File, uid: Int, sessionId: String): Int {
            val marker = "$ENV_KEY=$sessionId"
            var completed = 0
            for (dir in proc.listFiles().orEmpty()) {
                if (Thread.currentThread().isInterrupted) break
                if (dir.name.toIntOrNull() == null) continue
                try {
                    val status = File(dir, "status").readLines()
                    val owner = status.firstOrNull { it.startsWith("Uid:") }
                        ?.substringAfter(':')?.trim()?.takeWhile(Char::isDigit)?.toIntOrNull()
                    val state = status.firstOrNull { it.startsWith("State:") }
                        ?.substringAfter(':')?.trim()?.firstOrNull()
                    if (owner != uid || state != 'T') continue
                    // Pin the target before checking its session; a recycled PID cannot redirect the write.
                    Files.newOutputStream(File(dir, "reclaim").toPath(), StandardOpenOption.WRITE).use { control ->
                        if (marker !in File(dir, "environ").readText().split('\u0000')) return@use
                        if (!Thread.currentThread().isInterrupted) {
                            control.write("all".toByteArray())
                            completed++
                        }
                    }
                } catch (_: IOException) {
                    // The process exited, or this kernel/SELinux policy doesn't allow reclaim.
                } catch (_: SecurityException) {
                    // Keep the normal suspend/resume path on restricted devices.
                }
            }
            return completed
        }
    }
}
