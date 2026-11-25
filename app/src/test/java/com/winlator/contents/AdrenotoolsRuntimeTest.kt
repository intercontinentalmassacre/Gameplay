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
    fun `only the selected Turbo package needs the Gamescope guest wrapper path`() {
        val filesDir = temporary.root
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = filesDir
        }
        val manager = AdrenotoolsManager(context)
        val driverDir = File(filesDir, "contents/adrenotools/Turnip Turbo").apply { mkdirs() }
        val metadata = File(driverDir, "meta.json")
        val env = EnvVars().apply {
            put("ADRENOTOOLS_DRIVER_PATH", "$driverDir/")
            put("ADRENOTOOLS_DRIVER_NAME", "vulkan.freedreno.so")
        }

        metadata.writeText("""{"packageVersion":"26.1.6"}""")
        assertFalse(manager.isSd8Gen2TurboDriver(env))

        metadata.writeText("""{"packageVersion":"26.1.6-sd8gen2-turbo"}""")
        assertTrue(manager.isSd8Gen2TurboDriver(env))

        env.put("ADRENOTOOLS_DRIVER_NAME", "libvulkan_freedreno.so")
        assertFalse(manager.isSd8Gen2TurboDriver(env))
    }

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
