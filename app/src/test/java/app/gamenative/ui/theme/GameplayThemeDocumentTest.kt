package app.gamenative.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameplayThemeDocumentTest {
    @Test
    fun `duplicated theme keeps palettes and gets independent name`() {
        val original = GameplayThemeCodec.safeDocument("Slate")
        val duplicate = original.copy(name = "Slate copy")

        assertEquals("Slate", original.name)
        assertEquals("Slate copy", duplicate.name)
        assertEquals(original.dark, duplicate.dark)
        assertEquals(original.light, duplicate.light)

        val decoded = GameplayThemeCodec.decode(GameplayThemeCodec.encode(duplicate))
        assertTrue(decoded is GameplayThemeDecodeResult.Success)
        assertEquals("Slate copy", (decoded as GameplayThemeDecodeResult.Success).document.name)
    }

    @Test
    fun `high contrast is a persisted theme capability`() {
        val document = GameplayThemeCodec.safeDocument("Accessible").copy(
            tokens = GameplayThemeTokens(highContrast = true),
        )

        val decoded = GameplayThemeCodec.decode(GameplayThemeCodec.encode(document))

        assertTrue(decoded is GameplayThemeDecodeResult.Success)
        assertTrue((decoded as GameplayThemeDecodeResult.Success).document.tokens.highContrast)
    }

    @Test
    fun `acidic accent warns without invalidating the theme`() {
        val safe = GameplayThemeCodec.safeDocument("Neon test")
        val document = safe.copy(
            dark = safe.dark.copy(primary = "#00FF44", onPrimary = "#000000"),
        )

        assertTrue(GameplayThemeCodec.decode(GameplayThemeCodec.encode(document)) is GameplayThemeDecodeResult.Success)
        assertEquals(
            listOf(GameplayThemeWarning("dark.primary")),
            GameplayThemeCodec.warnings(document),
        )
    }
}
