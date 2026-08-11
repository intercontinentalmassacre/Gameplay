package com.winlator.xenvironment.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuBackendTypeTest {

    @Test
    fun resolvesArm64EcBackends() {
        assertEquals(CpuBackendType.FEX_ARM64EC, CpuBackendType.resolve(true, false, "FEXCore"))
        assertEquals(CpuBackendType.WOWBOX64_ARM64EC, CpuBackendType.resolve(true, false, "Box64"))
        assertEquals(CpuBackendType.WOWBOX64_ARM64EC, CpuBackendType.resolve(true, false, null))
    }

    @Test
    fun resolvesBox64Variants() {
        assertEquals(CpuBackendType.BOX64_GLIBC, CpuBackendType.resolve(false, true, "Box64"))
        assertEquals(CpuBackendType.BOX64_BIONIC, CpuBackendType.resolve(false, false, "Box64"))
    }

    @Test
    fun onlyFexAppliesFexEnvAndOnlyBoxFamilyAppliesBox64Env() {
        assertTrue(CpuBackendType.FEX_ARM64EC.appliesFexEnv())
        assertFalse(CpuBackendType.FEX_ARM64EC.appliesBox64Env())
        assertTrue(CpuBackendType.WOWBOX64_ARM64EC.appliesBox64Env())
        assertFalse(CpuBackendType.WOWBOX64_ARM64EC.appliesFexEnv())
        assertTrue(CpuBackendType.BOX64_BIONIC.appliesBox64Env())
        assertTrue(CpuBackendType.BOX64_GLIBC.appliesBox64Env())
    }

    @Test
    fun hodllOnlyForArm64Ec() {
        assertEquals("wowbox64.dll", CpuBackendType.WOWBOX64_ARM64EC.hodll())
        assertEquals("libwow64fex.dll", CpuBackendType.FEX_ARM64EC.hodll())
        assertNull(CpuBackendType.BOX64_BIONIC.hodll())
        assertNull(CpuBackendType.BOX64_GLIBC.hodll())
    }
}
