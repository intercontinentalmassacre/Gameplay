package app.gamenative.gamefixes

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamSourceGameFixesTest {
    @Test
    fun `VR fix keeps the existing Vulkan blacklist and does not duplicate entries`() {
        val extension = "VK_EXT_fragment_density_map"
        val combined = appendVulkanBlacklist("VK_EXT_other", extension)
        assertEquals("VK_EXT_other|$extension", combined)
        assertEquals(combined, appendVulkanBlacklist(combined, extension))
    }
}
