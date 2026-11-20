package com.winlator.contents

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DriverImportAndroidTest {
    @Test
    fun importSelectAndRejectIncompleteReplacement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = AdrenotoolsManager(context)
        val id = "Driver import test ${UUID.randomUUID()}"
        val installDir = File(context.filesDir, "contents/adrenotools/$id")
        val zip = File.createTempFile("driver-test-", ".zip", context.cacheDir)
        val lib = "test-driver.so"
        val metadata = JSONObject().put("schemaVersion", 1).put("name", id)
            .put("libraryName", lib).put("driverVersion", "1").put("minApi", 27).toString()
        fun writeArchive(withLibrary: Boolean) {
            ZipOutputStream(zip.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("package/meta.json"))
                out.write(metadata.toByteArray())
                if (withLibrary) {
                    out.putNextEntry(ZipEntry("package/$lib"))
                    File("/system/lib64/liblog.so").inputStream().use { it.copyTo(out) }
                }
            }
        }
        try {
            writeArchive(withLibrary = false)
            assertTrue(manager.installDriver(Uri.fromFile(zip)).isEmpty())
            assertFalse(installDir.exists())
            writeArchive(withLibrary = true)
            assertEquals(id, manager.installDriver(Uri.fromFile(zip)))
            assertTrue(manager.enumarateInstalledDrivers().contains(id))
            val env = EnvVars()
            manager.setDriverById(env, ImageFs.find(context), id)
            assertEquals(lib, env.get("ADRENOTOOLS_DRIVER_NAME"))
            assertEquals(installDir, File(env.get("ADRENOTOOLS_DRIVER_PATH")))
            assertTrue(manager.installDriver(Uri.fromFile(zip)).isEmpty())
            assertTrue(File(installDir, lib).isFile)
        } finally {
            installDir.deleteRecursively()
            zip.delete()
        }
    }
}
