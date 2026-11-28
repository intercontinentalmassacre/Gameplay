package org.buran.runtime.display

import org.buran.runtime.NativeRuntimeInstaller
import org.buran.runtime.buranFilesDir
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException

internal object GamescopeGuestInstall {
    private const val archive = "gamescope-runtime/rootfs.tar.zst"
    private const val version = "bannerlator-linuxfs-r9+gamescope-3.16.29-p4"
    private const val installedStamp = ".buran-installed-sha256"

    @Synchronized
    fun rootfs(context: Context): File {
        val parent = context.buranFilesDir.resolve("gamescope-runtime")
        val sha = try {
            context.assets.open("$archive.sha256").bufferedReader().use { it.readText() }.trim()
        } catch (_: FileNotFoundException) {
            // Developer builds without the bundled asset can still use a manually staged rootfs.
            return parent.resolve("rootfs").also {
                check(validRuntime(it)) { "Комплект LinuxFS для Gamescope отсутствует" }
            }
        }
        check(Regex("[0-9a-f]{64}").matches(sha)) { "Некорректная контрольная сумма Gamescope в APK" }

        val destination = parent.resolve("rootfs-$sha")
        if (validRuntime(destination) && readMarker(destination, installedStamp) == sha) return destination
        NativeRuntimeInstaller.install(context, "gamescope", archive, destination, sha)
        return destination
    }

    private fun validRuntime(root: File): Boolean =
        readMarker(root, ".buran-linuxfs") == version &&
            listOf("usr/local/bin/gamescope", "usr/bin/Xwayland", "usr/bin/gamescopereaper")
                .all { hasExecutableMode(root.resolve(it)) }

    private fun hasExecutableMode(file: File): Boolean = try {
        val mode = Os.stat(file.path).st_mode
        OsConstants.S_ISREG(mode) && (mode and OsConstants.S_IXUSR) != 0
    } catch (_: ErrnoException) {
        false
    }

    private fun readMarker(root: File, name: String): String? =
        root.resolve(name).takeIf(File::isFile)?.readText()?.trim()

}
