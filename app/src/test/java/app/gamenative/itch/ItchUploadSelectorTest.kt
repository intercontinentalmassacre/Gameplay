package app.gamenative.itch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchUploadSelectorTest {
    private fun upload(
        id: Long,
        filename: String,
        type: String = "default",
        channel: String = "",
        windows: Boolean? = true,
        displayName: String = "",
        size: Long = 1024,
        buildId: Long? = null,
        userVersion: String? = null,
        position: Int = 0,
    ): ItchUpload = ItchUpload(
        id = id,
        filename = filename,
        displayName = displayName,
        size = size,
        type = type,
        channelName = channel,
        platforms = windows?.let { mapOf("windows" to it) } ?: emptyMap(),
        position = position,
        build = if (buildId == null && userVersion == null) {
            null
        } else {
            ItchBuild(id = buildId, userVersion = userVersion)
        },
    )

    @Test
    fun `single windows game archive is chosen directly`() {
        val choice = ItchUploadSelector.choose(
            listOf(upload(1, "mygame-windows.zip", channel = "windows")),
        )
        assertTrue(choice is ItchUploadChoice.Single)
        assertEquals(1L, (choice as ItchUploadChoice.Single).upload.id)
    }

    @Test
    fun `extras never make the single game build ambiguous`() {
        val choice = ItchUploadSelector.choose(
            listOf(
                upload(1, "mygame-windows.zip", channel = "windows"),
                upload(2, "mygame-linux.zip", windows = false, channel = "linux"),
                upload(3, "soundtrack.zip", type = "soundtrack"),
                upload(4, "manual.pdf", type = "book"),
                upload(5, "mygame.html", type = "html"),
                upload(6, "mygame-editor.zip", displayName = "Level editor"),
            ),
        )
        assertTrue(choice is ItchUploadChoice.Single)
        assertEquals(1L, (choice as ItchUploadChoice.Single).upload.id)
    }

    @Test
    fun `two windows game builds require an explicit choice`() {
        val choice = ItchUploadSelector.choose(
            listOf(
                upload(1, "mygame-32.zip", channel = "windows"),
                upload(2, "mygame-64.zip", channel = "windows"),
            ),
        )
        assertTrue(choice is ItchUploadChoice.Multiple)
        val multiple = choice as ItchUploadChoice.Multiple
        assertEquals(2, multiple.uploads.size)
        assertEquals(2, multiple.gameCandidates)
    }

    @Test
    fun `a lone demo is never auto-installed`() {
        val choice = ItchUploadSelector.choose(
            listOf(upload(9, "mygame-demo.zip", displayName = "Demo build")),
        )
        assertTrue(choice is ItchUploadChoice.Multiple)
        assertEquals(ItchUploadRole.DEMO, (choice as ItchUploadChoice.Multiple).uploads.single().role)
    }

    @Test
    fun `browser only project reports no windows build`() {
        val choice = ItchUploadSelector.choose(
            listOf(upload(1, "mygame.html", type = "html", windows = null)),
        )
        assertTrue(choice is ItchUploadChoice.None)
        assertEquals(ItchUploadChoice.Reason.NO_WINDOWS_BUILD, (choice as ItchUploadChoice.None).reason)
    }

    @Test
    fun `no uploads at all is distinguished from no windows build`() {
        val choice = ItchUploadSelector.choose(emptyList())
        assertEquals(ItchUploadChoice.Reason.NO_UPLOADS, (choice as ItchUploadChoice.None).reason)
    }

    @Test
    fun `platform flags win over the filename`() {
        assertTrue(ItchUploadSelector.isWindowsBuild(upload(1, "game.zip", windows = true)))
        assertEquals(false, ItchUploadSelector.isWindowsBuild(upload(1, "game.zip", windows = false)))
        assertEquals(
            false,
            ItchUploadSelector.isWindowsBuild(
                ItchUpload(
                    id = 2,
                    filename = "game.zip",
                    platforms = mapOf("linux" to true),
                    channelName = "linux",
                ),
            ),
        )
    }

    @Test
    fun `channel and extension identify windows when platform flags are absent`() {
        assertTrue(
            ItchUploadSelector.isWindowsBuild(
                ItchUpload(id = 1, filename = "game.zip", platforms = emptyMap(), channelName = "windows-64"),
            ),
        )
        assertTrue(
            ItchUploadSelector.isWindowsBuild(
                ItchUpload(id = 2, filename = "setup.exe", platforms = emptyMap(), channelName = ""),
            ),
        )
        assertEquals(
            false,
            ItchUploadSelector.isWindowsBuild(
                ItchUpload(id = 3, filename = "game.zip", platforms = emptyMap(), channelName = "linux-64"),
            ),
        )
    }

    @Test
    fun `role classification uses type and filename tokens`() {
        assertEquals(ItchUploadRole.SOUNDTRACK, ItchUploadSelector.roleOf(upload(1, "ost.zip", type = "soundtrack")))
        assertEquals(ItchUploadRole.DEMO, ItchUploadSelector.roleOf(upload(2, "mygame-demo.zip")))
        assertEquals(ItchUploadRole.EDITOR, ItchUploadSelector.roleOf(upload(3, "mygame-editor.zip")))
        assertEquals(ItchUploadRole.MANUAL, ItchUploadSelector.roleOf(upload(4, "manual.zip")))
        // "demolition" must not be mistaken for a demo.
        assertEquals(ItchUploadRole.GAME, ItchUploadSelector.roleOf(upload(5, "demolition-derby.zip")))
    }

    @Test
    fun `candidates expose version size and channel for the picker`() {
        val candidate = ItchUploadSelector.toCandidate(
            upload(
                id = 42,
                filename = "MyGame-Win64.zip",
                channel = "windows",
                displayName = "MyGame 1.2 (64-bit)",
                size = 3L * 1024 * 1024,
                buildId = 9001,
                userVersion = "1.2.3",
            ),
        )
        assertEquals("MyGame 1.2 (64-bit)", candidate.title)
        assertEquals("1.2.3", candidate.versionLabel)
        assertEquals(9001L, candidate.buildId)
        assertEquals("3.0 MB", candidate.sizeLabel)
        assertEquals("windows", candidate.channelName)
    }
}
