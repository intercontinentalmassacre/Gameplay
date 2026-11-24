package app.gamenative

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrashContextTest {
    @After
    fun tearDown() {
        CrashContext.clear()
    }

    @Test
    fun `captures launch metadata and stage`() {
        CrashContext.beginLaunch("custom-game-42")
        CrashContext.update(
            gameName = "Example Game",
            containerId = "custom-game-42",
            runtime = "Wine/Proton=proton-10; DXVK/VKD3D=dxvk",
            gpu = "Adreno",
            launchStage = "launching_game",
        )

        val snapshot = CrashContext.snapshot()
        assertEquals("custom-game-42", snapshot.appId)
        assertEquals("Example Game", snapshot.gameName)
        assertEquals("custom-game-42", snapshot.containerId)
        assertEquals("Wine/Proton=proton-10; DXVK/VKD3D=dxvk", snapshot.runtime)
        assertEquals("Adreno", snapshot.gpu)
        assertEquals("launching_game", snapshot.launchStage)
    }

    @Test
    fun `trace export only includes the selected game's session`() {
        CrashContext.beginLaunch("game-a")
        val trace = app.gamenative.diagnostics.LaunchTrace
        val preview = trace.snapshot("game-a")
        CrashContext.beginLaunch("game-b")

        assertEquals(null, trace.snapshot("game-a").sessionId)
        assertEquals(emptyList<Any>(), trace.snapshot("game-a").events)
        assertFalse(preview.events.isEmpty())
        assertFalse(preview.sessionId == trace.snapshot("game-b").sessionId)
    }

    @Test
    fun `redacts common credentials from logs`() {
        val log = "Authorization: Bearer secret-token api_key=another-secret https://example.test/?token=url-secret"

        val sanitized = CrashHandler.sanitizeLogcat(log)

        assertFalse(sanitized.contains("secret-token"))
        assertFalse(sanitized.contains("another-secret"))
        assertFalse(sanitized.contains("url-secret"))
        assert(sanitized.contains("<redacted>"))
    }
}
