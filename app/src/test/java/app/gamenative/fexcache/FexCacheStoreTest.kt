package app.gamenative.fexcache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FexCacheStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun store(appId: String = "STEAM_1"): FexCacheStore {
        val root = temp.newFolder("state-$appId")
        val cache = temp.newFolder("cache-$appId")
        return FexCacheStore(
            appId = appId,
            rootDir = root,
            hostCacheDir = cache,
            windowsCacheDir = "C:\\ProgramData\\Gameplay\\FexCache\\$appId\\",
            clock = { 1_000L },
        )
    }

    private fun metadata(fexBuild: String = "build-a", config: String = "preset-a") = FexCacheMetadata(
        appId = "STEAM_1",
        fexVersion = "2611-gn-8",
        fexBuildHash = fexBuild,
        configHash = config,
        deviceFingerprint = "device-a",
        executablePath = "Haiku.exe",
        executableSize = 10,
        executableMtime = 20,
        executableSha256 = "exe",
        preparedAt = 30,
        prepareDurationMs = 40,
        compileDurationMs = 50,
        cacheBytes = 60,
    )

    @Test
    fun `replacing the game executable makes the published cache incompatible`() {
        val original = metadata()
        // Same game, same FEX/config, but the executable was updated (size, mtime, hash changed).
        val updatedExe = original.copy(executableSize = 11, executableMtime = 21, executableSha256 = "exe-2")
        assertFalse(original.compatibleWith(updatedExe))
        assertFalse(updatedExe.compatibleWith(original))
        assertTrue(original.compatibleWith(original.copy(preparedAt = 999)))
    }

    @Test
    fun `preparation is published only after a successful ready transition`() {
        val store = store()
        assertEquals(FexCacheState.NONE, store.readState().state)

        store.beginPreparation()
        assertEquals(FexCacheState.PREPARING, store.readState().state)
        assertTrue(store.readState().runStartedAt == 0L)

        store.markRunStarted()
        assertEquals(1_000L, store.readState().runStartedAt)

        store.markCompiling()
        assertEquals(FexCacheState.COMPILING, store.readState().state)

        store.markReady(metadata())
        val ready = store.readState()
        assertEquals(FexCacheState.READY, ready.state)
        assertNotNull(ready.metadata)
        assertTrue(ready.metadata!!.compatibleWith(metadata()))
    }

    @Test
    fun `a changed FEX build or preset invalidates the published cache`() {
        val published = metadata(fexBuild = "build-a", config = "preset-a")
        assertFalse(published.compatibleWith(metadata(fexBuild = "build-b", config = "preset-a")))
        assertFalse(published.compatibleWith(metadata(fexBuild = "build-a", config = "preset-b")))
        assertTrue(published.compatibleWith(metadata(fexBuild = "build-a", config = "preset-a")))
    }

    @Test
    fun `interrupted preparation is not left as ready`() {
        val store = store()
        store.beginPreparation()
        store.markRunStarted()
        store.markCompiling()
        store.markNeedsReprepare("compilation was interrupted")

        assertEquals(FexCacheState.NEEDS_REPREPARE, store.readState().state)
        assertFalse(store.readState().state == FexCacheState.READY)
    }

    @Test
    fun `clear removes only this game's cache and preserves saves next to it`() {
        val store = store()
        val codeMap = File(store.hostCacheDir, "codemap/new/game.bin").apply {
            parentFile.mkdirs()
            writeText("map")
        }
        val cacheFile = File(store.hostCacheDir, "cache/haiku-1234").apply {
            parentFile.mkdirs()
            writeText("x".repeat(128))
        }
        val save = File(store.hostCacheDir.parentFile, "save.dat").apply { writeText("save") }
        assertTrue(store.hasCodeMap())
        assertTrue(store.hasCacheArtifacts())

        store.beginPreparation()
        store.markReady(metadata())
        assertTrue(store.clear())

        assertFalse(codeMap.exists())
        assertFalse(cacheFile.exists())
        assertTrue(save.exists())
        assertEquals(FexCacheState.NONE, store.readState().state)
    }

    @Test
    fun `second preparation cannot acquire the same game lock`() {
        val store = store()
        val first = store.tryLock()
        assertNotNull(first)
        assertNull(store.tryLock())
        first!!.close()
        val again = store.tryLock()
        assertNotNull(again)
        again!!.close()
    }

    @Test
    fun `zero length code map is ignored`() {
        val store = store()
        val empty = File(store.hostCacheDir, "codemap/new/empty.bin").apply {
            parentFile.mkdirs()
            writeText("")
        }
        assertFalse(store.hasCodeMap())
        assertTrue(empty.exists())
    }
}
