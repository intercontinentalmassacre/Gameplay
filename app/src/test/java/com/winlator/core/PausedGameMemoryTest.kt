package com.winlator.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PausedGameMemoryTest {
    @get:Rule val proc = TemporaryFolder()

    @Test
    fun reclaimsOnlyStoppedProcessesOfThisUserAndSession() {
        val marker = "${PausedGameMemory.ENV_KEY}=game"
        fun process(pid: Int, state: Char, uid: Int, entry: String, supported: Boolean = true): File {
            val dir = proc.newFolder(pid.toString())
            File(dir, "status").writeText("State:\t$state (state)\nUid:\t$uid\t$uid\t$uid\t$uid\n")
            File(dir, "environ").writeText("HOME=/home\u0000$entry\u0000")
            return File(dir, "reclaim").also { if (supported) it.createNewFile() }
        }
        val eligible = process(40, 'T', 10001, marker)
        val otherSession = process(41, 'T', 10001, "${marker}2")
        val otherUser = process(42, 'T', 10002, marker)
        val running = process(43, 'R', 10001, marker)
        val unsupported = process(44, 'T', 10001, marker, supported = false)

        assertEquals(1, PausedGameMemory.reclaim(proc.root, 10001, "game"))
        assertEquals("all", eligible.readText())
        listOf(otherSession, otherUser, running).forEach { assertEquals("", it.readText()) }
        assertFalse(unsupported.exists())
    }
}
