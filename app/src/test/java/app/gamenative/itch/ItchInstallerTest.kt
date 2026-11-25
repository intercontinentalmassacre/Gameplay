package app.gamenative.itch

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ItchInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { stream ->
            entries.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
    }

    private fun exe(text: String): ByteArray = text.toByteArray()

    private suspend fun installZip(
        archive: File,
        layout: ItchInstallLayout,
        uploadId: Long = 100,
        buildId: Long? = 1,
        versionId: String = ItchInstaller.newVersionId(uploadId, buildId, 1_000L),
    ): ItchVersionMeta {
        val staging = layout.stagingDir(versionId)
        val root = ItchInstaller.extractArchive(archive, staging)
        val resolution = ItchInstaller.resolveLaunch(root)
        assertTrue("expected a launch target, got $resolution", resolution is ItchLaunchResolution.Selected)
        val spec = (resolution as ItchLaunchResolution.Selected).spec
        return ItchInstaller.commitVersion(
            gameRoot = root,
            layout = layout,
            meta = ItchVersionMeta(
                versionId = versionId,
                uploadId = uploadId,
                buildId = buildId,
                launchTarget = spec.target,
                launchArgs = spec.args,
            ),
        )
    }

    @Test
    fun `single wrapper folder is unwrapped and manifest recorded`() = runBlocking {
        val archive = File(temporaryFolder.root, "game.zip")
        zip(
            archive,
            mapOf(
                ".itch.toml" to exe("[[actions]]\nname = \"play\"\npath = \"Game.exe\"\n"),
                "Game.exe" to exe("v1"),
                "data/pack.bin" to exe("data"),
            ),
        )
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val staging = layout.stagingDir("v1")
        val root = ItchInstaller.extractArchive(archive, staging)

        // The ZIP root itself is the game folder because it has a manifest.
        assertEquals(root.canonicalFile, staging.canonicalFile)
        val meta = installZip(archive, layout, versionId = "v1")
        assertEquals("Game.exe", meta.launchTarget)
        assertEquals(3, meta.manifest.files.size)
        assertTrue(meta.manifest.totalBytes > 0)
        assertTrue(ItchInstaller.verifyVersion(layout, meta))
    }

    @Test
    fun `nested build folder is unwrapped`() = runBlocking {
        val archive = File(temporaryFolder.root, "nested.zip")
        zip(
            archive,
            mapOf(
                "My Game/game.exe" to exe("v1"),
                "My Game/readme.txt" to exe("hi"),
            ),
        )
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val root = ItchInstaller.extractArchive(archive, layout.stagingDir("nested"))
        assertEquals("My Game", root.name)
        assertEquals("game.exe", ItchInstaller.executableCandidates(root).single().path)
    }

    @Test
    fun `corrupt archive is rejected and leaves the previous install untouched`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val good = File(temporaryFolder.root, "good.zip")
        zip(good, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(good, layout, buildId = 1, versionId = "v1")
        ItchInstaller.activate(layout, v1)

        val corrupt = File(temporaryFolder.root, "corrupt.zip")
        corrupt.writeBytes(ByteArray(512) { (it * 31 % 255).toByte() })

        try {
            ItchInstaller.extractArchive(corrupt, layout.stagingDir("v2"))
            fail("expected extraction to fail")
        } catch (expected: Exception) {
            // expected
        }

        val state = ItchInstaller.readState(layout)
        assertEquals("v1", state?.activeVersionId)
        assertTrue(File(layout.versionDir("v1"), "game.exe").isFile)
        assertFalse(layout.versionDir("v2").exists())
    }

    @Test
    fun `update preserves player files created next to the executable`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))

        val v1Archive = File(temporaryFolder.root, "v1.zip")
        zip(v1Archive, mapOf("game.exe" to exe("v1"), "obsolete.bin" to exe("old")))
        val v1 = installZip(v1Archive, layout, uploadId = 100, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)
        val v1Dir = layout.versionDir(v1.versionId)
        File(v1Dir, "saves/slot1.sav").apply { parentFile.mkdirs() }.writeText("progress=42")
        File(v1Dir, "settings.ini").writeText("fullscreen=1")

        val v2Archive = File(temporaryFolder.root, "v2.zip")
        zip(v2Archive, mapOf("game.exe" to exe("v2"), "new.bin" to exe("new")))
        val v2 = installZip(v2Archive, layout, uploadId = 100, buildId = 2, versionId = "u100_b2")

        val carry = ItchInstaller.carryOverPlayerFiles(
            oldVersionDir = v1Dir,
            oldMeta = v1,
            newVersionDir = layout.versionDir(v2.versionId),
            newMeta = v2,
        )
        assertEquals(2, carry.copied)

        var repointed = ""
        val state = ItchInstaller.activate(layout, v2) { repointed = v2.versionId }
        assertEquals(v2.versionId, state.activeVersionId)
        assertEquals("u100_b1", state.previousVersionId)
        assertEquals(v2.versionId, repointed)

        val v2Dir = layout.versionDir(v2.versionId)
        assertEquals("progress=42", File(v2Dir, "saves/slot1.sav").readText())
        assertEquals("fullscreen=1", File(v2Dir, "settings.ini").readText())
        assertEquals("v2", File(v2Dir, "game.exe").readText())
        assertFalse(File(v2Dir, "obsolete.bin").exists())
        assertTrue(File(v2Dir, "new.bin").isFile)
    }

    @Test
    fun `player file is not overwritten when the new build ships the same path`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val v1Archive = File(temporaryFolder.root, "v1.zip")
        zip(v1Archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(v1Archive, layout, buildId = 1, versionId = "v1")
        ItchInstaller.activate(layout, v1)
        File(layout.versionDir(v1.versionId), "save.dat").writeText("player")

        val v2Archive = File(temporaryFolder.root, "v2.zip")
        zip(v2Archive, mapOf("game.exe" to exe("v2"), "save.dat" to exe("delivered-default")))
        val v2 = installZip(v2Archive, layout, buildId = 2, versionId = "v2")
        val carry = ItchInstaller.carryOverPlayerFiles(
            oldVersionDir = layout.versionDir(v1.versionId),
            oldMeta = v1,
            newVersionDir = layout.versionDir(v2.versionId),
            newMeta = v2,
        )
        assertEquals(0, carry.copied)
        assertEquals(1, carry.conflicts)
        assertEquals("delivered-default", File(layout.versionDir(v2.versionId), "save.dat").readText())
    }

    @Test
    fun `interrupted update with a complete new version is finished on recovery`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val v1Archive = File(temporaryFolder.root, "v1.zip")
        zip(v1Archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(v1Archive, layout, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)

        val v2Archive = File(temporaryFolder.root, "v2.zip")
        zip(v2Archive, mapOf("game.exe" to exe("v2")))
        val v2 = installZip(v2Archive, layout, buildId = 2, versionId = "u100_b2")

        // Simulate a crash after the new tree was prepared but before activation.
        ItchInstaller.writeJournal(
            layout,
            ItchUpdateJournal(
                phase = ItchUpdatePhase.PREPARED,
                targetVersionId = v2.versionId,
                previousVersionId = v1.versionId,
                startedAtMs = 5_000L,
            ),
        )

        var repointed: String? = null
        val recovery = ItchInstaller.recover(layout) { repointed = it.versionId }

        assertTrue(recovery.recovered)
        assertEquals(v2.versionId, repointed)
        assertEquals(v2.versionId, recovery.state?.activeVersionId)
        assertEquals(null, ItchInstaller.readJournal(layout))
        assertTrue(File(layout.versionDir(v2.versionId), "game.exe").isFile)
    }

    @Test
    fun `interrupted update with an incomplete new version keeps the old one launchable`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val v1Archive = File(temporaryFolder.root, "v1.zip")
        zip(v1Archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(v1Archive, layout, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)

        // Half-written version folder + journal, no completion metadata.
        val broken = layout.versionDir("u100_b2")
        broken.mkdirs()
        File(broken, "game.exe").writeText("truncated")
        ItchInstaller.writeJournal(
            layout,
            ItchUpdateJournal(
                phase = ItchUpdatePhase.PREPARED,
                targetVersionId = "u100_b2",
                previousVersionId = v1.versionId,
                startedAtMs = 6_000L,
            ),
        )

        val recovery = ItchInstaller.recover(layout)

        assertFalse(recovery.recovered)
        assertEquals(v1.versionId, recovery.state?.activeVersionId)
        assertTrue(recovery.discardedVersionIds.contains("u100_b2"))
        assertFalse(broken.exists())
        assertTrue(File(layout.versionDir(v1.versionId), "game.exe").isFile)
        assertEquals(null, ItchInstaller.readJournal(layout))
    }

    @Test
    fun `missing state file is rebuilt from the newest complete version`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "v1.zip")
        zip(archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(archive, layout, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)
        layout.stateFile.delete()

        var repointed: String? = null
        val recovery = ItchInstaller.recover(layout) { repointed = it.versionId }
        assertTrue(recovery.recovered)
        assertEquals("u100_b1", recovery.state?.activeVersionId)
        assertEquals("u100_b1", repointed)
    }

    @Test
    fun `verifyVersion catches tampered installs`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "v1.zip")
        zip(archive, mapOf("game.exe" to exe("v1"), "data.bin" to exe("1234")))
        val v1 = installZip(archive, layout, buildId = 1, versionId = "v1")
        assertTrue(ItchInstaller.verifyVersion(layout, v1))
        File(layout.versionDir("v1"), "data.bin").appendText("tampered")
        assertFalse(ItchInstaller.verifyVersion(layout, v1))
    }

    @Test
    fun `launch target can be changed from the card`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "multi.zip")
        zip(archive, mapOf("Game.exe" to exe("main"), "tools/SetupLauncher.exe" to exe("other")))
        val meta = installZip(archive, layout, buildId = 1, versionId = "v1")
        ItchInstaller.activate(layout, meta)

        val state = ItchInstaller.updateLaunchTarget(layout, "v1", "tools/SetupLauncher.exe")
        assertEquals("tools/SetupLauncher.exe", state.launchTarget)
        val reloaded = ItchInstaller.readMeta(layout, "v1")
        assertEquals("tools/SetupLauncher.exe", reloaded?.launchTarget)
    }

    @Test
    fun `executable candidates skip installers and uninstallers`() = runBlocking {
        val folder = temporaryFolder.newFolder("candidates")
        File(folder, "Game.exe").writeText("game")
        File(folder, "unins000.exe").writeText("uninstall")
        File(folder, "tools").mkdirs()
        File(folder, "tools/editor.exe").writeText("editor")

        val candidates = ItchInstaller.executableCandidates(folder).map { it.path }
        assertEquals(listOf("Game.exe", "tools/editor.exe"), candidates)
    }

    @Test
    fun `a zip with several executables asks for a selection`() = runBlocking {
        val archive = File(temporaryFolder.root, "multi.zip")
        zip(archive, mapOf("a.exe" to exe("a"), "b.exe" to exe("b")))
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val root = ItchInstaller.extractArchive(archive, layout.stagingDir("multi"))
        val resolution = ItchInstaller.resolveLaunch(root)
        assertTrue(resolution is ItchLaunchResolution.NeedsSelection)
        assertEquals(2, (resolution as ItchLaunchResolution.NeedsSelection).candidates.size)
    }

    @Test
    fun `failed activation keeps the previous version launchable and is recoverable`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val v1Archive = File(temporaryFolder.root, "v1.zip")
        zip(v1Archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(v1Archive, layout, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)

        val v2Archive = File(temporaryFolder.root, "v2.zip")
        zip(v2Archive, mapOf("game.exe" to exe("v2")))
        val v2 = installZip(v2Archive, layout, buildId = 2, versionId = "u100_b2")

        try {
            ItchInstaller.activate(layout, v2) { throw java.io.IOException("container repoint failed") }
            fail("expected activation to fail")
        } catch (expected: java.io.IOException) {
            // expected
        }

        // The old version is still the active one and can be launched.
        assertEquals(v1.versionId, ItchInstaller.readState(layout)?.activeVersionId)
        assertTrue(File(layout.versionDir(v1.versionId), "game.exe").isFile)
        assertEquals("v1", File(layout.versionDir(v1.versionId), "game.exe").readText())
        // The prepared version is kept so recovery can finish the swap.
        assertTrue(layout.versionDir(v2.versionId).isDirectory)
        assertNotNull(ItchInstaller.readJournal(layout))

        var repointed: String? = null
        val recovery = ItchInstaller.recover(layout) { repointed = it.versionId }
        assertTrue(recovery.recovered)
        assertEquals(v2.versionId, repointed)
        assertEquals(v2.versionId, ItchInstaller.readState(layout)?.activeVersionId)
    }

    @Test
    fun `recovery never deletes the active version even with corrupt metadata`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "v1.zip")
        zip(archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(archive, layout, buildId = 1, versionId = "u100_b1")
        ItchInstaller.activate(layout, v1)

        // Simulate a torn write of the completion metadata and a half-extracted folder.
        layout.metadataFile(v1.versionId).writeText("{not json")
        val junk = layout.versionDir("half-extracted")
        junk.mkdirs()
        File(junk, "game.exe").writeText("truncated")

        val recovery = ItchInstaller.recover(layout)

        assertTrue("active version folder must survive", layout.versionDir(v1.versionId).isDirectory)
        assertTrue(File(layout.versionDir(v1.versionId), "game.exe").isFile)
        assertEquals(v1.versionId, recovery.state?.activeVersionId)
        assertFalse(junk.exists())
    }

    @Test
    fun `uninstall removes the whole layout`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "v1.zip")
        zip(archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(archive, layout, buildId = 1, versionId = "v1")
        ItchInstaller.activate(layout, v1)
        ItchInstaller.uninstall(layout)
        assertFalse(layout.root.exists())
    }

    @Test
    fun `readMeta rejects a mismatched version id`() = runBlocking {
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "install"))
        val archive = File(temporaryFolder.root, "v1.zip")
        zip(archive, mapOf("game.exe" to exe("v1")))
        val v1 = installZip(archive, layout, buildId = 1, versionId = "v1")
        assertNotNull(ItchInstaller.readMeta(layout, "v1"))
        assertNull(ItchInstaller.readMeta(layout, "other"))
    }
}
