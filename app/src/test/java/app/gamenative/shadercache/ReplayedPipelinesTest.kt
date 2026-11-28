package app.gamenative.shadercache

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReplayedPipelinesTest {
    @get:Rule val temp = TemporaryFolder()

    private val driver = "turnip:25.1.0"
    private val uuid = "0123456789abcdef0123456789abcdef"

    private fun name(tag: Int, hash: Long) = "%024x%016x".format(tag, hash)

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun entry(tag: Int, hash: Long, payload: ByteArray = ByteArray(0)) =
        name(tag, hash).toByteArray() + le(payload.size) + le(1) + le(0) + le(payload.size) + payload

    private fun foz(file: File, vararg entries: ByteArray): File {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray() + byteArrayOf(0, 0, 0, 6))
        entries.forEach { out.write(it) }
        return file.apply { writeBytes(out.toByteArray()) }
    }

    private fun runFile(dir: File, index: Int, uuid: String = this.uuid) =
        File(dir, "${ReplayedPipelines.cachePath(dir, driver).name}.$uuid.$index.foz")

    private fun baseFile(dir: File) = File(dir, "${ReplayedPipelines.cachePath(dir, driver).name}.$uuid.foz")

    @Test fun readsOnlyPipelineEntries() {
        val cut = entry(6, 9, ByteArray(40) { 1 })
        val pool = foz(
            temp.newFile(),
            entry(4, 1, ByteArray(300) { 1 }),
            entry(6, 2, ByteArray(120) { 1 }),
            entry(7, 3),
            entry(9, 4, ByteArray(5)),
            entry(8, 5),
            cut.copyOf(cut.size - 5),
        )

        assertEquals(setOf(name(6, 2), name(7, 3), name(9, 4)), ReplayedPipelines.pipelineNames(pool))
        assertEquals(emptySet<String>(), ReplayedPipelines.pipelineNames(temp.newFile().apply { writeText("not a database") }))
        assertEquals(emptySet<String>(), ReplayedPipelines.pipelineNames(File(temp.root, "missing.foz")))
    }

    @Test fun foldsWhatEachReplayProcessWroteIntoTheDatabaseTheNextOneReads() {
        val dir = temp.newFolder()
        foz(runFile(dir, 1), entry(6, 1), entry(6, 2))
        foz(runFile(dir, 2), entry(7, 3))

        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 1), name(6, 2), name(7, 3)), ReplayedPipelines.pipelineNames(baseFile(dir)))
        assertFalse(runFile(dir, 1).exists())
        assertFalse(runFile(dir, 2).exists())

        foz(runFile(dir, 1), entry(6, 4))
        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 1), name(6, 2), name(7, 3), name(6, 4)), ReplayedPipelines.known(dir, driver))
        assertEquals(listOf(baseFile(dir).name), dir.list()!!.toList())
    }

    @Test fun keepsDriversApart() {
        val dir = temp.newFolder()
        foz(runFile(dir, 1), entry(6, 1))
        ReplayedPipelines.fold(dir, driver)
        val newerDriver = "turnip:26.0.0"
        foz(File(dir, "${ReplayedPipelines.cachePath(dir, newerDriver).name}.$uuid.1.foz"), entry(6, 2))
        ReplayedPipelines.fold(dir, newerDriver)

        assertEquals(setOf(name(6, 1)), ReplayedPipelines.known(dir, driver))
        assertEquals(setOf(name(6, 2)), ReplayedPipelines.known(dir, "turnip:26.0.0"))
        assertTrue(ReplayedPipelines.known(dir, "vortek:1").isEmpty())
    }

    @Test fun onlySkipsPipelinesPresentInEveryDeviceCacheForTheDriver() {
        val dir = temp.newFolder()
        val otherUuid = "abcdef0123456789abcdef0123456789"
        foz(runFile(dir, 1), entry(6, 1), entry(6, 2))
        foz(runFile(dir, 1, otherUuid), entry(6, 2), entry(6, 3))
        foz(File(dir, "${ReplayedPipelines.cachePath(dir, driver).name}.backup.foz"), entry(6, 4))

        ReplayedPipelines.fold(dir, driver)

        assertEquals(setOf(name(6, 2)), ReplayedPipelines.known(dir, driver))
        assertEquals(setOf(name(6, 1), name(6, 2)), ReplayedPipelines.pipelineNames(baseFile(dir)))
    }

    @Test fun whitelistContainsOnlyDistinctPendingPipelineHashes() {
        val first = foz(temp.newFile(), entry(4, 1), entry(6, 2), entry(7, 3))
        val second = foz(temp.newFile(), entry(7, 3), entry(9, 4))
        val whitelist = File(temp.root, "pending.foz")

        val pending = ReplayedPipelines.pending(listOf(first, second), setOf(name(6, 2)))

        assertTrue(ReplayedPipelines.writeWhitelist(whitelist, pending))
        assertEquals(setOf(name(7, 3), name(9, 4)), FozArchive.names(whitelist))
        assertEquals(16 + 2 * 56, whitelist.length().toInt())
    }

    @Test fun retriesOnceAndQuarantinesOnlyTheSamePendingSetWhenNeitherRunMakesProgress() {
        val stall = File(temp.root, "driver.stalled")
        val pending = ReplayedPipelines.pendingIdentity(setOf(name(6, 1), name(6, 2)))
        val reordered = ReplayedPipelines.pendingIdentity(setOf(name(6, 2), name(6, 1)))
        val changed = ReplayedPipelines.pendingIdentity(setOf(name(6, 2), name(6, 3)))

        ReplayedPipelines.recordAttempt(stall, pending, progressed = false)
        assertFalse(ReplayedPipelines.isStalled(stall, pending))
        ReplayedPipelines.recordAttempt(stall, reordered, progressed = false)
        assertTrue(ReplayedPipelines.isStalled(stall, pending))
        assertFalse(ReplayedPipelines.isStalled(stall, changed))

        ReplayedPipelines.recordAttempt(stall, changed, progressed = true)
        assertFalse(ReplayedPipelines.isStalled(stall, pending))
    }

    @Test fun writesEntriesFossilizeReadsAsEmpty() {
        val dir = temp.newFolder()
        foz(runFile(dir, 1), entry(6, 1))
        ReplayedPipelines.fold(dir, driver)

        val written = dir.listFiles()!!.single().readBytes()

        assertEquals(16 + 56, written.size)
        assertEquals(name(6, 1), String(written, 16, 40))
        assertEquals(listOf<Byte>(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), written.drop(56).toList())
    }
}
