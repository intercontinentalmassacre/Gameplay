package org.buran.runtime.display

import android.content.Context
import android.os.SystemClock
import android.system.Os
import java.io.File
import java.security.SecureRandom

internal class GamescopeX11 private constructor(
    val sharedTmp: File,
    private val session: File,
    private val cookie: ByteArray
) : AutoCloseable {
    val sessionDirectory: File get() = session
    val guestSession = "/tmp/${session.name}"
    private val authority = session.resolve("Xauthority")
    private val number = Regex(":[0-9]{1,5}")

    val guestEnvironment = mapOf(
        "WLR_XWAYLAND" to "$guestSession/Xwayland",
        "XAUTHORITY" to "$guestSession/Xauthority"
    )

    fun watchGameWindow(socket: String, executable: String, desktop: Boolean): GamescopeGameWindow =
        GamescopeGameWindow.open(File(socket), cookie, executable, desktop)

    fun watchPresentFrames(socket: String, executable: String, desktop: Boolean): GamescopePresentFrames? =
        GamescopePresentFrames.open(File(socket), cookie, executable, desktop)

    fun setFpsLimit(socket: String, fps: Int) {
        X11Requests.open(File(socket), cookie).use { it.setRootCardinal("GAMESCOPE_FPS_LIMIT", fps) }
    }

    fun awaitX11(process: Process, wineDrawsDesktopFrames: Boolean): Map<String, String> {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var previousError: Exception? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            check(process.isAlive) { "Gamescope завершился до запуска Xwayland (${process.exitValue()})" }
            val display = session.resolve("display").takeIf { it.isFile }?.readText()?.trim()
            if (display != null && number.matches(display)) {
                val socket = sharedTmp.resolve(".X11-unix/X${display.drop(1)}")
                try {
                    X11Requests.open(socket, cookie).use { connection ->
                        val manager = requireWindowManager(connection)
                        if (wineDrawsDesktopFrames) enableWineDesktopFrames(connection, manager)
                    }
                    return mapOf(
                        "DISPLAY" to display,
                        "XAUTHORITY" to authority.path,
                        "TMPDIR" to sharedTmp.path,
                        "BURAN_X11_SOCKET" to socket.path
                    )
                } catch (error: Exception) {
                    previousError = error
                }
            }
            Thread.sleep(50)
        }
        throw IllegalStateException("Сессия Xwayland не готова через 30 секунд", previousError)
    }

    private fun requireWindowManager(x11: X11Requests): Int {
        val supportingAtom = x11.internAtom("_NET_SUPPORTING_WM_CHECK")
        fun supportingWindow(window: Int) = x11.property(window, supportingAtom, 33) // WINDOW
            ?.takeIf { it.get(1).toInt() == 32 && it.getInt(16) == 1 }?.getInt(32)

        val manager = supportingWindow(x11.root)
        check(manager != null && manager != 0 && manager != x11.root && supportingWindow(manager) == manager) {
            "Gamescope ещё не зарегистрировал оконный менеджер X11"
        }
        return manager
    }

    private fun enableWineDesktopFrames(x11: X11Requests, manager: Int) {
        val nameAtom = x11.internAtom("_NET_WM_NAME")
        val utf8Atom = x11.internAtom("UTF8_STRING")
        fun managerName(atom: Int, type: Int) = x11.property(manager, atom, type)
            ?.takeIf { it.get(1).toInt() == 8 && it.getInt(8) == type && it.getInt(12) == 0 }
            ?.let { String(it.array(), 32, it.getInt(16), Charsets.UTF_8) }

        val name = managerName(nameAtom, utf8Atom) ?: managerName(39, 31) // WM_NAME, STRING
        check(name != null) { "Gamescope ещё не опубликовал имя оконного менеджера X11" }
        if (name != "steamcompmgr") return

        // Proton disables Windows captions and borders when it detects "steamcompmgr".
        // Change the advertised name only on this private virtual-desktop display.
        val desktopName = "Gamescope (Buran Wine Desktop)"
        x11.replaceProperty(manager, nameAtom, utf8Atom, desktopName.toByteArray(Charsets.UTF_8))
        check(managerName(nameAtom, utf8Atom) == desktopName) { "Не удалось установить имя оконного менеджера X11" }
    }

    override fun close() {
        session.deleteRecursively()
    }

    companion object {
        fun prepare(context: Context, sharedPixmaps: Boolean = false): GamescopeX11 {
            val sharedTmp = X11Connection.sharedTmp(context)
            check(sharedTmp.isDirectory || sharedTmp.mkdirs()) { "Не удалось подготовить каталог X11" }
            val session = java.nio.file.Files.createTempDirectory(sharedTmp.toPath(), "buran-gamescope-").toFile()
            val cookie = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val result = GamescopeX11(sharedTmp, session, cookie)
            try {
                X11Connection.writeAuthority(result.authority, cookie)
                Os.chmod(result.authority.path, 0b110000000)
                val launcher = session.resolve("Xwayland")
                val shmOption = if (sharedPixmaps) "" else " -extension MIT-SHM"
                launcher.writeText(
                    "#!/bin/sh\n" +
                        "set -eu\n" +
                        "printf '%s\\n' \"\$1\" > '${result.guestSession}/display'\n" +
                        "exec /usr/bin/Xwayland \"\$@\" -auth \"\$XAUTHORITY\" -nolisten tcp$shmOption\n"
                )
                Os.chmod(launcher.path, 0b111000000)
                return result
            } catch (error: Exception) {
                result.close()
                throw error
            }
        }
    }
}
