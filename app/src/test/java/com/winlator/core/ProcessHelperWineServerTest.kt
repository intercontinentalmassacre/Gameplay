package com.winlator.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessHelperWineServerTest {
    @Test
    fun recognizesServerWithOrWithoutLinker() {
        assertTrue(ProcessHelper.isWineServerCmdline(arrayOf("wineserver")))
        assertTrue(ProcessHelper.isWineServerCmdline(arrayOf("/system/bin/linker64", "/data/bin/wineserver", "-d0")))
    }

    @Test
    fun ignoresClientsAndOtherWineProcesses() {
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("/data/bin/wineserver", "-k")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("wineserver", "--kill=9")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("wineserver", "-w")))
        assertFalse(ProcessHelper.isWineServerCmdline(arrayOf("wine", "services.exe")))
    }
}
