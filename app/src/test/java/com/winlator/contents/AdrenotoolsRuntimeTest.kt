package com.winlator.contents

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.winlator.core.DefaultVersion
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdrenotoolsRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `installed driver supplies its exact path and library to the Vulkan loader`() {
        val filesDir = temporary.root
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = filesDir
        }
        val driverId = "Turnip v26.2.0 R4"
        val driverDir = File(filesDir, "contents/adrenotools/$driverId").apply { mkdirs() }
        val libraryName = "libvulkan_freedreno.so"
        File(driverDir, "meta.json").writeText("""{"name":"$driverId","libraryName":"$libraryName"}""")
        File(driverDir, libraryName).writeBytes(ByteArray(64).apply {
            byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1).copyInto(this)
            this[16] = 3
            this[18] = 183.toByte()
        })
        val imageFs = ImageFs.find(File(filesDir, "imagefs"))
        val manager = AdrenotoolsManager(context)

        val previousDefault = DefaultVersion.WRAPPER
        try {
            DefaultVersion.WRAPPER = driverId
            val env = EnvVars()
            assertTrue(manager.setDriverById(env, imageFs, driverId))
            assertEquals("$driverDir/", env.get("ADRENOTOOLS_DRIVER_PATH"))
            assertEquals(libraryName, env.get("ADRENOTOOLS_DRIVER_NAME"))
            assertEquals(imageFs.libDir.toString(), env.get("ADRENOTOOLS_HOOKS_PATH"))

            val systemEnv = EnvVars()
            assertFalse(manager.setDriverById(systemEnv, imageFs, "System"))
            assertTrue(systemEnv.isEmpty)
        } finally {
            DefaultVersion.WRAPPER = previousDefault
        }

        File(driverDir, libraryName).writeBytes(ByteArray(0))
        val corruptEnv = EnvVars()
        assertFalse(manager.setDriverById(corruptEnv, imageFs, driverId))
        assertTrue(corruptEnv.isEmpty)
    }
}
