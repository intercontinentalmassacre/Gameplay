package com.winlator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DXVKInlineConfigTest {
    @Test
    fun passesIndividualOptionsWithoutShellQuoting() {
        val config = KeyValueSet("maxDeviceMemory=1024,maxFeatureLevel=11_0,customDevice=1234:abcd:Test GPU")
        assertEquals(
            listOf(
                "dxgi.maxDeviceMemory = 1024", "dxgi.maxSharedMemory = 1024", "d3d11.maxFeatureLevel = 11_0",
                "dxgi.customDeviceId = 1234", "dxgi.customVendorId = abcd", "dxgi.customDeviceDesc = \"Test GPU\"",
                "d3d9.customDeviceId = 1234", "d3d9.customVendorId = abcd", "d3d9.customDeviceDesc = \"Test GPU\"",
            ),
            DXVKHelper.buildInlineConfig(config).split(';'),
        )
    }

    @Test
    fun zeroLimitsProduceAnEmptyConfig() {
        assertEquals("", DXVKHelper.buildInlineConfig(KeyValueSet("maxDeviceMemory=0,maxFeatureLevel=0")))
    }
}
