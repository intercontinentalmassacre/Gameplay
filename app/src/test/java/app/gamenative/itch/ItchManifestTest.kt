package app.gamenative.itch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ItchManifestTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `parses a minimal play action`() {
        val actions = ItchManifest.parse(
            """
            [[actions]]
            name = "play"
            path = "Overland.exe"
            """.trimIndent(),
        )
        assertEquals(1, actions.size)
        assertEquals("play", actions.single().name)
        assertEquals("Overland.exe", actions.single().path)
        assertTrue(actions.single().args.isEmpty())
    }

    @Test
    fun `parses arguments and platform`() {
        val actions = ItchManifest.parse(
            """
            [[actions]]
            name = "play"
            path = "sample.exe"
            platform = "windows"
            args = ["--that", "--is", "a lot=of-arguments"]
            """.trimIndent(),
        )
        val action = actions.single()
        assertEquals(listOf("--that", "--is", "a lot=of-arguments"), action.args)
        assertEquals("windows", action.platform)
    }

    @Test
    fun `handles comments CRLF and multi-line arrays`() {
        val actions = ItchManifest.parse(
            "[[actions]]\r\n" +
                "name = \"play\" # the highlighted action\r\n" +
                "path = \"game.exe\"\r\n" +
                "args = [\r\n" +
                "  \"--fullscreen\",\r\n" +
                "  \"--skip-intro\",\r\n" +
                "]\r\n",
        )
        assertEquals(listOf("--fullscreen", "--skip-intro"), actions.single().args)
    }

    @Test
    fun `ignores unknown tables and locale overrides`() {
        val actions = ItchManifest.parse(
            """
            [[prereqs]]
            name = "vcredist-2010-x86"

            [[actions]]
            name = "play"
            path = "FooBar.exe"

            [[actions.locales.fr]]
            name = "Jouer"

            [[actions]]
            name = "editor"
            path = "tools/editor.exe"
            platform = "windows"
            """.trimIndent(),
        )
        assertEquals(2, actions.size)
        assertEquals("play", actions[0].name)
        assertEquals("editor", actions[1].name)
    }

    @Test
    fun `windows play action wins over utilities`() {
        val actions = listOf(
            ItchManifestAction("editor", "editor.exe", platform = "windows"),
            ItchManifestAction("manual", "manual.exe", platform = "windows"),
            ItchManifestAction("play", "game.exe", platform = "windows"),
        )
        assertEquals("play", ItchManifest.pickWindowsAction(actions)?.name)
    }

    @Test
    fun `linux actions and urls are not selected`() {
        val actions = listOf(
            ItchManifestAction("play", "game.py", platform = "linux"),
            ItchManifestAction("forums", "https://example.itch.io/community", platform = "windows"),
            ItchManifestAction("play", "Game.EXE", platform = null),
        )
        assertEquals("Game.EXE", ItchManifest.pickWindowsAction(actions)?.path)
    }

    @Test
    fun `resolveLaunch reads the manifest from the game root`() {
        val root = temporaryFolder.newFolder("game")
        File(root, ".itch.toml").writeText(
            """
            [[actions]]
            name = "play"
            path = "bin/Game.exe"
            args = ["-windowed"]
            """.trimIndent(),
        )
        File(root, "bin").mkdirs()
        File(root, "bin/Game.exe").writeBytes(byteArrayOf(0x4D, 0x5A))

        val spec = ItchManifest.resolveLaunch(root)
        assertNotNull(spec)
        assertEquals("bin/Game.exe", spec!!.target)
        assertEquals(listOf("-windowed"), spec.args)
        assertTrue(spec.fromManifest)
        assertEquals("play", spec.actionName)
    }

    @Test
    fun `resolveLaunch returns null when the action target is missing`() {
        val root = temporaryFolder.newFolder("missing")
        File(root, ".itch.toml").writeText(
            """
            [[actions]]
            name = "play"
            path = "nope.exe"
            """.trimIndent(),
        )
        assertNull(ItchManifest.resolveLaunch(root))
    }
}
