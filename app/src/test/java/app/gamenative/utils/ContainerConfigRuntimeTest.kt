package app.gamenative.utils

import com.winlator.container.ContainerData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContainerConfigRuntimeTest {

    private fun baseConfig() = ContainerData(
        name = "Test Container",
        screenSize = "1280x720",
    )

    @Test
    fun `identical configs report no changes`() {
        val old = baseConfig()
        val new = baseConfig()

        assertTrue(ContainerConfigRuntime.changedFieldNames(old, new).isEmpty())
        assertFalse(ContainerConfigRuntime.requiresRestart(old, new))
    }

    @Test
    fun `touch and input changes are live safe`() {
        val old = baseConfig()
        val new = old.copy(
            touchscreenMode = !old.touchscreenMode,
            shooterMode = !old.shooterMode,
            gestureConfig = "{\"tap\":\"left_click\"}",
            shooterConfig = "{\"sensitivity\":2.0}",
            disableMouseInput = !old.disableMouseInput,
            enableXInput = !old.enableXInput,
            enableDInput = !old.enableDInput,
            dinputMapperType = 2,
        )

        val changed = ContainerConfigRuntime.changedFieldNames(old, new)
        assertEquals(
            setOf(
                "touchscreenMode",
                "shooterMode",
                "gestureConfig",
                "shooterConfig",
                "disableMouseInput",
                "enableXInput",
                "enableDInput",
                "dinputMapperType",
            ),
            changed,
        )
        assertFalse(ContainerConfigRuntime.requiresRestart(old, new))
    }

    @Test
    fun `hud effects and suspend policy changes are live safe`() {
        val old = baseConfig()
        val new = old.copy(
            showFPS = !old.showFPS,
            suspendPolicy = "never",
            sharpnessEffect = "CAS",
            sharpnessLevel = 80,
            sharpnessDenoise = 60,
            externalDisplayMode = "touchpad",
            externalDisplaySwap = !old.externalDisplaySwap,
        )

        assertFalse(ContainerConfigRuntime.requiresRestart(old, new))
    }

    @Test
    fun `runtime changes require restart`() {
        val old = baseConfig()

        val restartCases = listOf(
            old.copy(wineVersion = "proton-9.0") to "wineVersion",
            old.copy(dxwrapper = "vkd3d") to "dxwrapper",
            old.copy(dxwrapperConfig = "async=1") to "dxwrapperConfig",
            old.copy(graphicsDriver = "turnip") to "graphicsDriver",
            old.copy(displayRenderer = "gl") to "displayRenderer",
            old.copy(renderer = "vulkan") to "renderer",
            old.copy(envVars = "DXVK_HUD=1") to "envVars",
            old.copy(drives = "D=/storage") to "drives",
            old.copy(wincomponents = "direct3d=0") to "wincomponents",
            old.copy(box64Preset = "PERFORMANCE") to "box64Preset",
            old.copy(box64Version = "0.3.4") to "box64Version",
            old.copy(fexcorePreset = "PERFORMANCE") to "fexcorePreset",
            old.copy(emulator = "Box64") to "emulator",
            old.copy(cpuList = "0,1,2,3") to "cpuList",
            old.copy(screenSize = "1920x1080") to "screenSize",
            old.copy(audioDriver = "alsa") to "audioDriver",
        )

        restartCases.forEach { (new, expectedField) ->
            val restartFields = ContainerConfigRuntime.restartRequiredChangedFields(old, new)
            assertTrue("Expected restart for $expectedField", expectedField in restartFields)
            assertTrue(ContainerConfigRuntime.requiresRestart(old, new))
        }
    }

    @Test
    fun `mixed live and runtime changes require restart`() {
        val old = baseConfig()
        val new = old.copy(
            touchscreenMode = !old.touchscreenMode,
            graphicsDriver = "turnip",
        )

        val restartFields = ContainerConfigRuntime.restartRequiredChangedFields(old, new)
        assertEquals(setOf("graphicsDriver"), restartFields)
        assertTrue(ContainerConfigRuntime.requiresRestart(old, new))
    }

    @Test
    fun `name change does not require restart`() {
        val old = baseConfig()
        val new = old.copy(name = "Renamed")

        assertEquals(setOf("name"), ContainerConfigRuntime.changedFieldNames(old, new))
        assertFalse(ContainerConfigRuntime.requiresRestart(old, new))
    }
}
