package app.gamenative.utils.installscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallScriptExecutorTest {

    // --- mergeWithLanguage tests ---

    @Test
    fun mergeWithLanguage_appliesOverrides() {
        val action = RegistryAction(
            keyPath = "HKLM\\Software\\Test",
            values = RegistryValues(
                strings = mapOf("BaseName" to "BaseValue", "OtherName" to "OtherValue"),
                dwords = mapOf("BaseCount" to 1L),
            ),
            languageOverrides = mapOf(
                "french" to RegistryValues(
                    strings = mapOf("BaseName" to "FrenchValue"),
                    dwords = mapOf("FrenchCount" to 42L),
                ),
            ),
        )

        val merged = InstallScriptExecutor.mergeWithLanguage(action, "french")

        assertEquals("FrenchValue", merged.strings["BaseName"])
        assertEquals("OtherValue", merged.strings["OtherName"])
        assertEquals(1L, merged.dwords["BaseCount"])
        assertEquals(42L, merged.dwords["FrenchCount"])
    }

    @Test
    fun mergeWithLanguage_returnsBaseWhenNoOverride() {
        val action = RegistryAction(
            keyPath = "HKLM\\Software\\Test",
            values = RegistryValues(
                strings = mapOf("Name" to "Value"),
            ),
            languageOverrides = mapOf(
                "french" to RegistryValues(strings = mapOf("Name" to "FrenchValue")),
            ),
        )

        val merged = InstallScriptExecutor.mergeWithLanguage(action, "german")

        assertEquals("Value", merged.strings["Name"])
        assertFalse(merged.strings.containsValue("FrenchValue"))
    }

    // --- matchesOS tests ---

    @Test
    fun matchesOS_acceptsWhenNoRequirement() {
        assertTrue(InstallScriptExecutor.matchesOS(null, is64Bit = false))
        assertTrue(InstallScriptExecutor.matchesOS(null, is64Bit = true))
    }

    @Test
    fun matchesOS_rejects32BitWhen64BitRequired() {
        val requirement = OSRequirement(is64BitWindows = true)
        assertTrue(InstallScriptExecutor.matchesOS(requirement, is64Bit = true))
        assertFalse(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    @Test
    fun matchesOS_rejectsWrongOsType() {
        val requirement = OSRequirement(osType = "7")
        assertFalse(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    @Test
    fun matchesOS_acceptsMatchingOsType() {
        val requirement = OSRequirement(osType = "10")
        assertTrue(InstallScriptExecutor.matchesOS(requirement, is64Bit = false))
    }

    // --- stripHivePrefix tests ---

    @Test
    fun stripHivePrefix_stripsHKLM() {
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("HKLM\\Software\\Test"))
    }

    @Test
    fun stripHivePrefix_stripsHKCU() {
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("HKCU\\Software\\Test"))
    }

    @Test
    fun stripHivePrefix_stripsFullNames() {
        assertEquals(
            "Software\\Test",
            InstallScriptExecutor.stripHivePrefix("HKEY_LOCAL_MACHINE\\Software\\Test"),
        )
        assertEquals(
            "Software\\Test",
            InstallScriptExecutor.stripHivePrefix("HKEY_CURRENT_USER\\Software\\Test"),
        )
    }

    @Test
    fun stripHivePrefix_isCaseInsensitive() {
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("hklm\\Software\\Test"))
        assertEquals("Software\\Test", InstallScriptExecutor.stripHivePrefix("hkcu\\Software\\Test"))
    }
}
