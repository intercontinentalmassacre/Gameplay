package org.buran.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuranCpuSelectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun box64PackageMustMatchGuestArm64GlibcInterpreter() {
        assertTrue(BuranCpuSelection.isGlibcBox64(executable(183, "/lib/ld-linux-aarch64.so.1")))
        assertFalse(BuranCpuSelection.isGlibcBox64(executable(183, "/system/bin/linker64")))
        assertFalse(BuranCpuSelection.isGlibcBox64(executable(62, "/lib/ld-linux-aarch64.so.1")))
        assertFalse(BuranCpuSelection.isGlibcBox64(temp.newFile()))
    }

    private fun executable(machine: Int, interpreter: String): File {
        val path = (interpreter + "\u0000").toByteArray(Charsets.US_ASCII)
        val elf = ByteBuffer.allocate(120 + path.size).order(ByteOrder.LITTLE_ENDIAN)
        elf.put(0, 0x7f).put(1, 'E'.code.toByte()).put(2, 'L'.code.toByte()).put(3, 'F'.code.toByte())
        elf.put(4, 2).put(5, 1)
        elf.putShort(16, 3).putShort(18, machine.toShort())
        elf.putLong(32, 64).putShort(54, 56).putShort(56, 1)
        elf.putInt(64, 3).putLong(72, 120).putLong(96, path.size.toLong())
        elf.position(120)
        elf.put(path)
        return temp.newFile().apply { writeBytes(elf.array()) }
    }
}
