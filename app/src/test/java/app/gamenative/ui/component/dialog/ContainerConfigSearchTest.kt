package app.gamenative.ui.component.dialog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerConfigSearchTest {
    @Test
    fun `matches container tabs by label or keywords`() {
        assertTrue(matchesContainerConfigTab("Graphics", "dxvk vkd3d vulkan", "VKD3D"))
        assertTrue(matchesContainerConfigTab("Wine", "proton runtime", "proton"))
        assertFalse(matchesContainerConfigTab("General", "resolution display", "controller"))
        assertFalse(matchesContainerConfigTab("Graphics", "dxvk", ""))
    }
}
