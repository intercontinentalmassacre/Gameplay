package org.buran.runtime.display

import org.buran.runtime.buranCacheDir
import org.buran.runtime.buranFilesDir
import android.content.Context
import android.os.Process.myUid
import android.system.Os
import android.system.OsConstants
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object GamescopeRuntime {
    private data class Session(val x11: GamescopeX11, val pidFile: File, val proot: File)
    private val sessions = ConcurrentHashMap<Process, Session>()

    private fun log(context: Context) = context.buranFilesDir.resolve("gamescope-runtime.txt")

    fun start(context: Context, waylandDir: File, displayName: String, sharedPixmaps: Boolean = false): Process {
        require(Regex("wayland-[0-9]+").matches(displayName)) { "Некорректное имя Wayland-сокета" }
        val rootfs = GamescopeGuestInstall.rootfs(context)
        check(waylandDir.resolve(displayName).exists()) { "Wayland-сокет Android не готов" }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val proot = native.resolve("libburan_gamescope_proot.so")
        val loader = native.resolve("libburan_gamescope_loader.so")
        val talloc = native.resolve("libburan_gamescope_talloc.so")
        check(listOf(proot, loader, talloc).all(File::isFile)) { "В APK нет компонентов PRoot для ARM64" }

        val cache = context.buranCacheDir.resolve("gamescope").apply { mkdirs() }
        val libs = cache.resolve("android-libs").apply { mkdirs() }
        val tallocLink = libs.resolve("libtalloc.so.2")
        val newLink = libs.resolve("libtalloc.so.2.new")
        newLink.delete()
        Os.symlink(talloc.path, newLink.path)
        Os.rename(newLink.path, tallocLink.path)
        val shm = cache.resolve("shm").apply { mkdirs() }
        val x11 = GamescopeX11.prepare(context, sharedPixmaps)
        var pidFile: File? = null
        try {
            val x11Transport = File.createTempFile("x11-filesystem-", ".so", x11.sessionDirectory)
            context.assets.open("gamescope-runtime/x11-filesystem.so").use { input ->
                x11Transport.outputStream().use { output -> input.copyTo(output) }
            }
            val pid = File.createTempFile("proot-", ".pid", cache)
            pidFile = pid
            val command = mutableListOf("/system/bin/sh", "-c",
                "printf '%s\\n' \"\$\$\" > \"\$1\" || exit 1; shift; exec \"\$@\"",
                "buran-gamescope", pid.path, proot.path, "--kill-on-exit", "-i", "${myUid()}:${Os.getgid()}",
                "-r", rootfs.path, "-w", "/root")
            fun bind(spec: String) { command.addAll(listOf("-b", spec)) }
            for (path in listOf("/dev", "/proc", "/sys", waylandDir.path)) bind(path)
            bind("${x11.sharedTmp.path}:/tmp")
            bind("${shm.path}:/dev/shm")
            for (spec in kgslBinds(cache)) bind(spec)

            val guestEnv = mapOf(
                "HOME" to "/root", "PATH" to "/usr/local/bin:/usr/bin", "TMPDIR" to "/tmp", "LANG" to "C",
                "XDG_RUNTIME_DIR" to waylandDir.path, "WAYLAND_DISPLAY" to displayName,
                "VK_DRIVER_FILES" to "/usr/share/vulkan/icd.d/freedreno_icd.json",
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "LD_PRELOAD" to "${x11.guestSession}/${x11Transport.name}:/usr/local/lib/libblsession.so",
            ) + x11.guestEnvironment
            command.addAll(listOf("/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "DISPLAY"))
            command.addAll(guestEnv.map { (key, value) -> "$key=$value" })
            command.addAll(listOf("/usr/local/bin/gamescope", "--backend", "wayland", "--expose-wayland",
                "-f", "-W", "1280", "-H", "720", "-w", "1280", "-h", "720", "--",
                "/bin/sh", "-c", "while IFS= read -r line; do :; done"))
            val process = ProcessBuilder(command).apply {
                environment().remove("LD_PRELOAD")
                environment()["LD_LIBRARY_PATH"] = libs.path
                environment()["PROOT_LOADER"] = loader.path
                environment()["PROOT_TMP_DIR"] = cache.path
                redirectErrorStream(true)
                redirectOutput(log(context))
            }.start()
            sessions[process] = Session(x11, pid, proot)
            return process
        } catch (error: Exception) {
            x11.close()
            pidFile?.delete()
            throw error
        }
    }

    fun awaitX11(context: Context, process: Process, wineDrawsDesktopFrames: Boolean): Map<String, String> {
        val session = checkNotNull(sessions[process]) { "Сессия Gamescope не запущена" }
        return try {
            session.x11.awaitX11(process, wineDrawsDesktopFrames)
        } catch (error: Exception) {
            throw IllegalStateException("Gamescope не открыл Xwayland. Журнал ${log(context).name}", error)
        }
    }

    fun watchGameWindow(
        process: Process, x11: Map<String, String>, executable: String, desktop: Boolean = false
    ): GamescopeGameWindow {
        val session = checkNotNull(sessions[process]) { "Сессия Gamescope не запущена" }
        return session.x11.watchGameWindow(x11.getValue("BURAN_X11_SOCKET"), executable, desktop)
    }

    fun setFpsLimit(process: Process, x11: Map<String, String>, fps: Int) {
        checkNotNull(sessions[process]).x11.setFpsLimit(x11.getValue("BURAN_X11_SOCKET"), fps)
    }

    fun watchPresentFrames(
        process: Process, x11: Map<String, String>, executable: String, desktop: Boolean,
    ): GamescopePresentFrames? =
        checkNotNull(sessions[process]).x11.watchPresentFrames(x11.getValue("BURAN_X11_SOCKET"), executable, desktop)

    fun stop(process: Process) {
        val session = sessions.remove(process) ?: return
        var interrupted = Thread.interrupted()
        fun waitForExit(millis: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    return process.waitFor(remaining, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            return true
        }
        try {
            try {
                process.outputStream.close()
            } catch (_: IOException) {
                // The pipe may already have closed if PRoot exited first.
            }
            if (waitForExit(2_000)) return
            // Termux PRoot ignores SIGTERM and uses SIGQUIT to stop all traced processes.
            runCatching {
                val pid = session.pidFile.readText().trim().toInt()
                require(pid > 0)
                val expected = "${session.proot.path}\u0000".toByteArray()
                val actual = ByteArray(expected.size)
                DataInputStream(File("/proc/$pid/cmdline").inputStream()).use { input ->
                    input.readFully(actual)
                }
                if (process.isAlive && actual.contentEquals(expected)) Os.kill(pid, OsConstants.SIGQUIT)
            }
            if (!waitForExit(1_000)) {
                process.destroyForcibly()
                waitForExit(1_000)
            }
        } finally {
            session.x11.close()
            session.pidFile.delete()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    internal fun kgslBinds(cache: File): List<String> {
        val kgsl = "/dev/kgsl-3d0"
        val device = Os.stat(kgsl).st_rdev
        val major = ((device shr 8) and 0xfff) or ((device shr 32) and 0xfff.inv().toLong())
        val minor = (device and 0xff) or ((device shr 12) and 0xff.inv().toLong())
        val node = "renderD$minor"
        val alias = cache.resolve("drm/dri").apply { mkdirs() }
        alias.resolve(node).createNewFile()
        val sys = cache.resolve("drm/sys").apply { mkdirs() }
        val sysDevice = sys.resolve("$major:$minor/device").apply { mkdirs() }
        val render = sysDevice.resolve("drm/$node").apply { mkdirs() }
        render.resolve("dev").writeText("$major:$minor\n")
        sysDevice.resolve("uevent").writeText("DRIVER=kgsl-3d0\nMODALIAS=platform:kgsl-3d0\n")
        val subsystem = sysDevice.resolve("subsystem")
        if (!java.nio.file.Files.isSymbolicLink(subsystem.toPath())) Os.symlink("/sys/bus/platform", subsystem.path)
        // This guest render-node alias refers to the same Android GPU device and keeps its access checks.
        return listOf("${sys.path}:/sys/dev/char", "${alias.path}:/dev/dri", "$kgsl:/dev/dri/$node")
    }
}
