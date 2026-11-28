package org.buran.runtime

import java.io.RandomAccessFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyVc80RuntimeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun similarlyNamedAndSizedInstallerMustMatchKnownPayload() {
        val installer = temp.newFile("vcredist_x86.exe")
        RandomAccessFile(installer, "rw").use { it.setLength(2_686_232) }

        assertFalse(LegacyVc80Runtime.matchesInstaller(installer))
        assertFalse(LegacyVc80Runtime.isInstalled(temp.newFolder("prefix"), installer))
    }

    @Test
    fun assemblyMustMatchTheExactX86RtmIdentity() {
        val identity = "x86_microsoft.vc80.crt_1fc8b3b9a1e18e3b_8.0.50727."
        assertTrue(LegacyVc80Runtime.matchesAssemblyName("${identity}42_none_deadbeef"))
        assertTrue(LegacyVc80Runtime.matchesAssemblyName("${identity}42_x-ww_0de06acd"))
        assertFalse(LegacyVc80Runtime.matchesAssemblyName("${identity}762_none_deadbeef"))
        assertFalse(LegacyVc80Runtime.matchesAssemblyName("${identity}9672_none_deadbeef"))
        assertFalse(LegacyVc80Runtime.matchesAssemblyName("${identity.replace("x86_", "amd64_")}42_none_deadbeef"))
    }
}
