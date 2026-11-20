package com.winlator.fexcore

import com.winlator.core.envvars.EnvVars
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FEXCoreManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `existing FEX configuration survives launch and teardown`() {
        val root = temp.newFolder()
        val config = File(root, ".fex-emu/Config.json").apply {
            parentFile?.mkdirs()
            writeText("""{"Config":{"TSOEnabled":"0","CustomOption":"keep this"}}""")
        }
        val original = config.readText()
        val options = EnvVars("FEX_TSOENABLED=1")

        FEXCoreManager.deleteContainerConfig(root)
        assertFalse(FEXCoreManager.writeContainerConfig(root, options))
        FEXCoreManager.deleteContainerConfig(root)

        assertEquals(original, config.readText())
    }

    @Test
    fun `stale Gameplay configuration can be replaced and removed`() {
        val root = temp.newFolder()
        val config = File(root, ".fex-emu/Config.json")
        assertTrue(FEXCoreManager.writeContainerConfig(root, EnvVars("FEX_TSOENABLED=1")))
        assertTrue(FEXCoreManager.writeContainerConfig(root, EnvVars("FEX_TSOENABLED=0")))
        assertEquals(mapOf("TSOEnabled" to "0"), configOf(config.readText()))

        FEXCoreManager.deleteContainerConfig(root)

        assertFalse(config.exists())
    }

    private val builtInPresets = listOf(
        FEXCorePreset.STABILITY,
        FEXCorePreset.COMPATIBILITY,
        FEXCorePreset.INTERMEDIATE,
        FEXCorePreset.PERFORMANCE,
        FEXCorePreset.EXTREME,
        FEXCorePreset.DENUVO,
    )

    private fun configOf(content: String): Map<String, String> {
        val config = JSONObject(content).getJSONObject("Config")
        return config.keys().asSequence().associateWith { config.getString(it) }
    }

    private fun envOf(envVars: EnvVars): Map<String, String> = envVars.associateWith { envVars.get(it) }

    private fun envName(option: String) = "FEX_" + option.uppercase()

    @Test
    fun `app config turns on vector and memcpy TSO and keeps the Steam client off code caches`() {
        val config = configOf(FEXCoreManager.APP_CONFIG_CONTENT)
        assertEquals(
            mapOf(
                "Multiblock" to "0",
                "X87ReducedPrecision" to "0",
                "TSOEnabled" to "1",
                "VectorTSOEnabled" to "1",
                "MemcpySetTSOEnabled" to "1",
                "HalfBarrierTSOEnabled" to "1",
                "MonoHacks" to "0",
                "EnableCodeCachingWIP" to "0",
            ),
            config,
        )
    }

    @Test
    fun `intermediate keeps its values, split between Config json and the environment`() {
        val env = FEXCorePresetManager.getEnvVars(null, FEXCorePreset.INTERMEDIATE)

        val taken = FEXCoreManager.takeAppConfigOptions(env)

        assertTrue(env.isEmpty)
        assertEquals(
            mapOf(
                "TSOEnabled" to "1",
                "VectorTSOEnabled" to "0",
                "MemcpySetTSOEnabled" to "0",
                "HalfBarrierTSOEnabled" to "1",
                "X87ReducedPrecision" to "1",
                "Multiblock" to "1",
            ),
            configOf(FEXCoreManager.containerConfigContent(taken)),
        )
    }

    @Test
    fun `no preset loses or changes an option`() {
        for (preset in builtInPresets) {
            val original = envOf(FEXCorePresetManager.getEnvVars(null, preset))
            val env = FEXCorePresetManager.getEnvVars(null, preset)

            val taken = FEXCoreManager.takeAppConfigOptions(env)
            val fromFile = configOf(FEXCoreManager.containerConfigContent(taken)).mapKeys { envName(it.key) }

            assertTrue(preset, fromFile.keys.none { env.has(it) })
            assertEquals(preset, original, envOf(env) + fromFile)
        }
    }
}
