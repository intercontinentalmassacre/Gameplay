package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgVkManagerConfigTest {
    @Test
    fun configUsesSchemaAcceptedByBundledNativeParser() {
        val config = LsfgVkManager.buildConfigToml(
            dllPath = "/root/.local/share/lsfg-vk/Lossless.dll",
            enabled = true,
            multiplier = 3,
            flowScale = 0.8f,
            performanceMode = true,
        )

        assertTrue(config.contains("version = 1"))
        assertTrue(config.contains("[[game]]"))
        assertTrue(config.contains("exe = \"gamenative-lsfg\""))
        assertTrue(config.contains("multiplier = 3"))
        assertTrue(config.contains("no_fp16 = false"))
        assertTrue(config.contains("hdr_mode = false"))
        assertTrue(config.contains("experimental_present_mode = \"fifo\""))
        assertFalse(config.contains("[[profile]]"))
        assertFalse(config.contains("LSFGVK_PROFILE"))
    }

    @Test
    fun disabledConfigKeepsLayerInNativePassthroughMode() {
        val config = LsfgVkManager.buildConfigToml(
            dllPath = "C:\\Lossless Scaling\\Lossless.dll",
            enabled = false,
            multiplier = 4,
            flowScale = 2f,
            performanceMode = true,
        )

        assertTrue(config.contains("dll = \"C:\\\\Lossless Scaling\\\\Lossless.dll\""))
        assertTrue(config.contains("multiplier = 1"))
        assertTrue(config.contains("flow_scale = 1.00"))
        assertTrue(config.contains("performance_mode = false"))
    }
}
