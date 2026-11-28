package org.buran.runtime.display

import android.os.SystemClock
import java.io.File
import java.io.IOException

/** Samples completed Xwayland presentations for the active Windows game's drawable. */
internal class GamescopePresentFrames private constructor(
    private val window: GamescopeGameWindow,
    private val events: X11Requests,
    private val opcode: Int,
    private var nativeFrames: Long,
) : AutoCloseable {
    private val windowEvents = mutableMapOf<Int, Int>()
    private var nextWindowCheck = 0L
    private var nextEventId = 1

    @Synchronized
    fun readFps(): Float? {
        val frames = nativeFrames
        if (frames == 0L) return null
        val now = SystemClock.elapsedRealtime()
        if (now >= nextWindowCheck) {
            val windows = window.presentWindows()
            (windowEvents.keys - windows).forEach { id ->
                select(id, windowEvents.remove(id)!!, 0)
                PresentFramesNative.track(frames, id, 0, false)
            }
            (windows - windowEvents.keys).forEach { id ->
                val eventId = events.resourceId(nextEventId++)
                select(id, eventId, 2) // PresentCompleteNotifyMask
                windowEvents[id] = eventId
                PresentFramesNative.track(frames, id, eventId, true)
            }
            nextWindowCheck = now + (if (windows.isEmpty()) 500L else 2_000L)
        }
        val fps = PresentFramesNative.read(frames, now)
        if (fps < 0f) throw IOException("X11 Present connection closed or invalid")
        return fps.takeIf(Float::isFinite)
    }

    private fun select(windowId: Int, eventId: Int, mask: Int) {
        events.send(opcode, 16, minorOpcode = 3) { // PresentSelectInput
            putInt(eventId).putInt(windowId).putInt(mask)
        }
    }

    @Synchronized
    override fun close() {
        if (nativeFrames == 0L) return
        PresentFramesNative.close(nativeFrames)
        nativeFrames = 0
        try {
            events.close()
        } finally {
            window.close()
        }
    }

    companion object {
        fun open(path: File, cookie: ByteArray, executable: String, desktop: Boolean): GamescopePresentFrames? {
            val events = X11Requests.open(path, cookie)
            try {
                val opcode = events.queryExtension("Present") ?: run {
                    events.close()
                    return null
                }
                val window = GamescopeGameWindow.open(path, cookie, executable, desktop)
                try {
                    // Kotlin has finished reading this event connection. Rust owns the duplicated
                    // descriptor and is its only reader; Kotlin still writes subscriptions.
                    val frames = events.duplicateDescriptor().use { PresentFramesNative.open(it.fd, opcode) }
                    if (frames == 0L) throw IOException("Could not duplicate X11 Present connection")
                    return GamescopePresentFrames(window, events, opcode, frames)
                } catch (error: Throwable) {
                    window.close()
                    throw error
                }
            } catch (error: Throwable) {
                events.close()
                throw error
            }
        }
    }
}

private object PresentFramesNative {
    init { System.loadLibrary("buran_present") }
    @JvmStatic external fun open(fd: Int, opcode: Int): Long
    @JvmStatic external fun track(frames: Long, window: Int, event: Int, active: Boolean)
    @JvmStatic external fun read(frames: Long, nowMs: Long): Float
    @JvmStatic external fun close(frames: Long)
}
