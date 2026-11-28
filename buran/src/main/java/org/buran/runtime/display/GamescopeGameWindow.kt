package org.buran.runtime.display

import android.util.Log
import java.io.File

internal class GamescopeGameWindow private constructor(
    private val x11: X11Requests,
    private val executable: String,
    private val desktop: Boolean
) : AutoCloseable {
    private val root = x11.root
    private var lastFocus = ""
    private val focusAtom = x11.internAtom("GAMESCOPE_FOCUSED_WINDOW")
    private val typeAtom = x11.internAtom("_NET_WM_WINDOW_TYPE")
    private val normalAtom = x11.internAtom("_NET_WM_WINDOW_TYPE_NORMAL")

    fun isReady(): Boolean = findWindow(logFocus = true) != null

    private fun findWindow(logFocus: Boolean = false): Int? {
        // Gamescope selects this global focus only after the window has a completed buffer commit.
        val focus = x11.property(root, focusAtom, 6)?.takeIf { it.get(1).toInt() == 32 && it.getInt(16) == 1 }
            ?.getInt(32) ?: 0
        val classes = if (focus == 0 || focus == root) emptyList() else windowClasses(focus)
        val description = "focus=0x${focus.toUInt().toString(16)} class=${classes.joinToString("/")}"
        if (logFocus && description != lastFocus) {
            Log.i("BuranWindow", description)
            lastFocus = description
        }
        if (classes.any { it.equals(executable, ignoreCase = true) } && isMainWindow(focus)) return focus
        if (!desktop || classes.none { it.equals("explorer.exe", ignoreCase = true) }) return null

        // Wine uses the explorer window as the virtual root for its application windows.
        return children(focus).firstOrNull { child ->
            windowClasses(child).any { it.equals(executable, ignoreCase = true) } && isMainWindow(child)
        }
    }

    fun presentWindows(): Set<Int> {
        val window = findWindow() ?: return emptySet()
        val windows = linkedSetOf(window)
        val pending = ArrayDeque<Int>().apply { add(window) }
        while (pending.isNotEmpty() && windows.size < 64) {
            children(pending.removeFirst()).take(64 - windows.size).forEach {
                if (windows.add(it)) pending.add(it)
            }
        }
        return windows
    }

    private fun isMainWindow(window: Int): Boolean {
        val attributes = x11.request(3, 8) { putInt(window) } ?: return false // GetWindowAttributes
        if (attributes.getShort(12).toInt() != 1 || attributes.get(26).toInt() != 2) return false
        val type = x11.property(window, typeAtom, 4) // ATOM
        if (type != null && type.getInt(8) != 0 &&
            (type.get(1).toInt() != 32 || type.getInt(16) == 0 || type.getInt(32) != normalAtom)
        ) return false
        val transient = x11.property(window, 68, 33) // WM_TRANSIENT_FOR, WINDOW
        if (transient == null || transient.getInt(8) != 0) return false
        val geometry = x11.request(14, 8) { putInt(window) } ?: return false // GetGeometry
        return (geometry.getShort(16).toInt() and 0xffff) > 1 &&
            (geometry.getShort(18).toInt() and 0xffff) > 1
    }

    private fun windowClasses(window: Int): List<String> {
        val reply = x11.property(window, 67, 31) ?: return emptyList() // WM_CLASS, STRING
        if (reply.get(1).toInt() != 8 || reply.getInt(8) != 31) return emptyList()
        return String(reply.array(), 32, reply.getInt(16), Charsets.UTF_8).split('\u0000', limit = 3).take(2)
    }

    private fun children(window: Int): IntArray {
        val reply = x11.request(15, 8) { putInt(window) } ?: return intArrayOf() // QueryTree
        return IntArray(reply.getShort(16).toInt() and 0xffff) { reply.getInt(32 + it * 4) }
    }

    override fun close() = x11.close()

    companion object {
        fun open(path: File, cookie: ByteArray, executable: String, desktop: Boolean): GamescopeGameWindow {
            val x11 = X11Requests.open(path, cookie)
            return try {
                GamescopeGameWindow(x11, executable.substringAfterLast('/').substringAfterLast('\\'), desktop)
            } catch (error: Exception) {
                x11.close()
                throw error
            }
        }
    }
}
