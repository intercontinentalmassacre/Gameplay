package com.winlator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringUtilsTest {
    @Test
    fun `fullscreen class substring matching ignores letter case`() {
        assertTrue(StringUtils.containsIgnoreCase("wine: GameLauncher.EXE", "gamelauncher.exe"))
        assertFalse(StringUtils.containsIgnoreCase("wine: explorer.exe", "GameLauncher.EXE"))
        assertFalse(StringUtils.containsIgnoreCase(null, "GameLauncher.EXE"))
    }
}
