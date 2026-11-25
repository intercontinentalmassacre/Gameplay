package com.winlator.container

import com.winlator.core.OnExtractFileListener
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContainerPrefixRegistryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `refresh preserves HKCU and backs up the hives it replaces`() {
        val container = temporary.newFolder()
        val prefix = File(container, ".wine").apply { mkdir() }
        File(prefix, "user.reg").writeText("game saves")
        File(prefix, "system.reg").writeText("old system")
        File(prefix, "userdef.reg").writeText("old defaults")
        val extract = ContainerManager.protectPrefixRegistry(container, null)

        extract.write(File(prefix, "user.reg"), "base user")
        extract.write(File(prefix, "system.reg"), "new system")
        extract.write(File(prefix, "userdef.reg"), "new defaults")
        extract.write(File(prefix, "system.reg"), "fallback system")

        assertEquals("game saves", File(prefix, "user.reg").readText())
        assertFalse(File(prefix, "user.reg.prev").exists())
        assertEquals("fallback system", File(prefix, "system.reg").readText())
        assertEquals("old system", File(prefix, "system.reg.prev").readText())
        assertEquals("new defaults", File(prefix, "userdef.reg").readText())
        assertEquals("old defaults", File(prefix, "userdef.reg.prev").readText())
    }

    @Test
    fun `new prefixes receive default HKCU and extraction filters still apply`() {
        val container = temporary.newFolder()
        val prefix = File(container, ".wine").apply { mkdir() }
        val extract = ContainerManager.protectPrefixRegistry(container) { file, _ ->
            file.takeUnless { it.name == "system.reg" }
        }

        extract.write(File(prefix, "user.reg"), "base user")
        extract.write(File(prefix, "system.reg"), "base system")

        assertEquals("base user", File(prefix, "user.reg").readText())
        assertFalse(File(prefix, "user.reg.prev").exists())
        assertFalse(File(prefix, "system.reg").exists())
    }

    private fun OnExtractFileListener.write(file: File, text: String) {
        onExtractFile(file, text.length.toLong())?.writeText(text)
    }
}
