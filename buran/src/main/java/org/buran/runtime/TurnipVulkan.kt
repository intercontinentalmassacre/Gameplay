package org.buran.runtime

import android.content.Context
import android.system.Os
import java.io.File

internal object TurnipVulkan {
    @Synchronized
    fun environment(context: Context): Map<String, String> {
        val directory = context.codeCacheDir.resolve("wine-vulkan-loader").apply { mkdirs() }
        val native = File(context.applicationInfo.nativeLibraryDir)
        val bridge = native.resolve("libburan_vulkan_bridge.so")
        check(bridge.isFile) { "В APK нет Vulkan bridge для ARM64" }
        val loader = directory.resolve("libvulkan.so.1")
        if (loader.canonicalFile != bridge.canonicalFile) {
            val staged = directory.resolve(".loader.new")
            staged.delete()
            Os.symlink(bridge.path, staged.path)
            Os.rename(staged.path, loader.path)
        }

        val driver = TurnipDrivers.selected(context)
        return mapOf(
            "BURAN_TURNIP_DRIVER_DIR" to (driver.directory.path + "/"),
            "BURAN_TURNIP_DRIVER_NAME" to driver.libraryName,
            "BURAN_TURNIP_LOADER_DIR" to directory.path,
        )
    }
}
