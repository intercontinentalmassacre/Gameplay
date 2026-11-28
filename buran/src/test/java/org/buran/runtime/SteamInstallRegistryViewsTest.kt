package org.buran.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamInstallRegistryViewsTest {
    @Test
    fun steamUsesWin32FirstButCanReadExistingWin64Markers() {
        assertEquals(
            listOf("/reg:32", "/reg:64"),
            SteamInstallDependencies.registryViews("HKLM\\SOFTWARE\\AGEIA Technologies", is64Bit = true),
        )
    }

    @Test
    fun explicitWow6432NodeStaysInTheNativeRegistryView() {
        assertEquals(
            listOf("/reg:64"),
            SteamInstallDependencies.registryViews("HKLM\\SOFTWARE\\wow6432node\\AGEIA Technologies", is64Bit = true),
        )
    }

    @Test
    fun win32PrefixesUseTheirDefaultViewWithoutFlags() {
        assertEquals(
            listOf(null),
            SteamInstallDependencies.registryViews("HKLM\\SOFTWARE\\AGEIA Technologies", is64Bit = false),
        )
    }
}
