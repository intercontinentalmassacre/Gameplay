package com.winlator.core

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WineRegistryEditorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun unchangedDwordDoesNotReplaceRegistryButChangedDwordIsWritten() {
        val key = "Software\\Valve\\Steam\\Apps\\CommonRedist\\DirectX"
        val registry = temporary.newFile("system.reg")
        val original = "WINE REGISTRY Version 2\n\n[${key.replace("\\", "\\\\")}] 1730000000\n" +
            "\"DXSetup\"=dword:00000001\n\n"
        registry.writeText(original)
        Files.setLastModifiedTime(registry.toPath(), FileTime.fromMillis(1_700_000_000_000))
        val originalModifiedTime = Files.getLastModifiedTime(registry.toPath())

        WineRegistryEditor(registry).use { it.setDwordValue(key, "DXSetup", 1) }
        assertEquals(original, registry.readText())
        assertEquals(originalModifiedTime, Files.getLastModifiedTime(registry.toPath()))

        WineRegistryEditor(registry).use { it.setDwordValue(key, "DXSetup", 2) }
        assertEquals(original.replace("dword:00000001", "dword:00000002"), registry.readText())
    }
}
