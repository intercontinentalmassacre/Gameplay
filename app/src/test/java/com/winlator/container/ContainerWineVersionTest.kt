package com.winlator.container

import com.winlator.core.DefaultVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerWineVersionTest {
    @Test
    fun `missing and mismatched Wine on Bionic use the Bionic runtime`() {
        for (wineVersion in listOf(null, Container.DEFAULT_WINE_VERSION)) {
            val container = Container("test")
            container.loadData(JSONObject().apply {
                put("containerVariant", Container.BIONIC)
                if (wineVersion != null) put("wineVersion", wineVersion)
                put("extraData", JSONObject().put("appVersion", "1"))
            })

            assertTrue(container.hasRepairedWineVersion())
            assertEquals(DefaultVersion.BIONIC_WINE_VERSION, container.wineVersion)
            assertEquals(Container.DEFAULT_WINE_VERSION, container.getExtra("appliedWineVersion"))
        }
    }

    @Test
    fun `explicit Wine choices are preserved and legacy configs still use GLIBC`() {
        val container = Container("test")
        container.loadData(JSONObject())
        assertEquals(Container.GLIBC, container.containerVariant)
        assertEquals(Container.DEFAULT_WINE_VERSION, container.wineVersion)

        container.loadData(JSONObject()
            .put("containerVariant", Container.BIONIC)
            .put("wineVersion", "proton-9.0-x86_64"))
        assertEquals("proton-9.0-x86_64", container.wineVersion)
        assertFalse(container.hasRepairedWineVersion())
    }
}
