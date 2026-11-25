package app.gamenative.itch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchUpdateCheckerTest {
    private fun upload(
        id: Long = 10,
        buildId: Long? = 100,
        userVersion: String? = "1.0",
        updatedAt: String = "2026-01-01T00:00:00Z",
        size: Long = 100,
        filename: String = "game-windows.zip",
    ) = ItchUpload(
        id = id,
        filename = filename,
        size = size,
        updatedAt = updatedAt,
        platforms = mapOf("windows" to true),
        build = buildId?.let { ItchBuild(id = it, userVersion = userVersion) },
    )

    private fun zipRevision(
        uploadId: Long? = 10,
        buildId: Long? = 100,
        updatedAt: String? = "2026-01-01T00:00:00Z",
        size: Long = 100,
    ) = ItchInstalledRevision(
        kind = ItchInstallerKind.ZIP,
        uploadId = uploadId,
        buildId = buildId,
        userVersion = "1.0",
        fileUpdatedAt = updatedAt,
        fileSize = size,
        filename = "game-windows.zip",
    )

    @Test
    fun `same butler build id is the only up-to-date claim`() {
        val decision = ItchUpdateChecker.evaluate(zipRevision(), listOf(upload()))
        assertEquals(ItchUpdateStatus.UP_TO_DATE, decision.status)
        assertTrue(decision.canAutoUpdate.not())
    }

    @Test
    fun `new butler build is an update for the same upload`() {
        val decision = ItchUpdateChecker.evaluate(
            zipRevision(),
            listOf(upload(buildId = 101, updatedAt = "2026-02-02T00:00:00Z").copy(id = 10)),
        )
        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertEquals(ItchUpdateReason.BUILD_CHANGED, decision.reason)
        assertEquals(10L, decision.upload?.id)
        assertTrue(decision.canAutoUpdate)
    }

    @Test
    fun `hand uploaded file with unchanged metadata never claims latest`() {
        val decision = ItchUpdateChecker.evaluate(
            zipRevision(buildId = null),
            listOf(upload(buildId = null, updatedAt = "2026-01-01T00:00:00Z")),
        )
        assertEquals(ItchUpdateStatus.VERSION_UNKNOWN, decision.status)
        assertEquals(ItchUpdateReason.MANUAL_UPLOAD_LIMITED_EVIDENCE, decision.reason)
        assertFalse(decision.canAutoUpdate)
    }

    @Test
    fun `hand uploaded file whose updated_at changed is an update`() {
        val decision = ItchUpdateChecker.evaluate(
            zipRevision(buildId = null),
            listOf(upload(buildId = null, updatedAt = "2026-03-03T00:00:00Z")),
        )
        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertEquals(ItchUpdateReason.FILE_CHANGED, decision.reason)
        assertTrue(decision.canAutoUpdate)
    }

    @Test
    fun `size change is the fallback evidence when timestamps are missing`() {
        val decision = ItchUpdateChecker.evaluate(
            zipRevision(buildId = null, updatedAt = null, size = 100),
            listOf(upload(buildId = null, updatedAt = "", size = 250)),
        )
        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertEquals(ItchUpdateReason.FILE_CHANGED, decision.reason)
    }

    @Test
    fun `stored upload disappearing does not switch channel automatically`() {
        val decision = ItchUpdateChecker.evaluate(
            zipRevision(uploadId = 10),
            listOf(upload(id = 11, buildId = 200)),
        )
        assertEquals(ItchUpdateStatus.UPLOAD_MISSING, decision.status)
        assertEquals(ItchUpdateReason.UPLOAD_REMOVED, decision.reason)
        assertFalse(decision.canAutoUpdate)
        assertEquals(null, decision.upload)
    }

    @Test
    fun `installer rebuilds are flagged as manual updates`() {
        val installed = zipRevision().copy(kind = ItchInstallerKind.EXE)
        val decision = ItchUpdateChecker.evaluate(installed, listOf(upload(buildId = 150)))
        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertTrue(decision.manualInstaller)
        assertFalse(decision.canAutoUpdate)
    }

    @Test
    fun `not installed project is reported as such`() {
        val decision = ItchUpdateChecker.evaluate(null, listOf(upload()))
        assertEquals(ItchUpdateStatus.NOT_INSTALLED, decision.status)
    }

    @Test
    fun `revision marker records the butler build for later checks`() {
        val revision = ItchUpdateChecker.revisionFor(
            ItchInstallerKind.ZIP,
            upload(buildId = 777, userVersion = "2.5", updatedAt = "2026-04-04T00:00:00Z", size = 4242),
        )
        assertEquals(777L, revision.buildId)
        assertEquals("2.5", revision.userVersion)
        assertEquals("2026-04-04T00:00:00Z", revision.fileUpdatedAt)
        assertEquals(4242L, revision.fileSize)
        assertEquals(10L, revision.uploadId)
    }
}
