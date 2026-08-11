package app.gamenative.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeShowcaseDocumentTest {

    private fun locateThemeDirectory(): File {
        val workingDir = File(System.getProperty("user.dir"))
        sequenceOf(workingDir, workingDir.parentFile)
            .filterNotNull()
            .map { File(it, "docs/themes") }
            .firstOrNull(File::isDirectory)
            ?.let { return it }
        throw IllegalStateException("docs/themes not found from ${workingDir.absolutePath}")
    }

    @Test
    fun showcaseThemesAreValidatedV2Documents() {
        val themes = requireNotNull(locateThemeDirectory().listFiles { file ->
            file.extension == "json" && file.name.endsWith(".gameplay-theme.json")
        }).sortedBy { it.name }

        assertEquals(4, themes.size)
        val focusStyles = mutableSetOf<String>()
        val panelWidths = mutableSetOf<Int>()
        themes.forEach { file ->
            when (val result = GameplayThemeCodec.decode(file.readText())) {
                is GameplayThemeDecodeResult.Success -> {
                    assertEquals(GameplayThemeDocument.CURRENT_SCHEMA_VERSION, result.document.schemaVersion)
                    assertTrue("${file.name} needs a visible focus ring", result.document.dark.focusRingColor != null)
                    assertTrue("${file.name} needs density tokens", result.document.tokens.density.isNotBlank())
                    focusStyles += result.document.tokens.focusRingStyle
                    panelWidths += result.document.tokens.panelMaxWidthDp
                }

                is GameplayThemeDecodeResult.Error -> {
                    throw AssertionError("${file.name} is not a valid theme: ${result.reason}")
                }
            }
        }
        assertTrue("Showcase themes should exercise more than one focus-ring style", focusStyles.size > 1)
        assertTrue("Showcase themes should exercise different panel layouts", panelWidths.size > 1)
    }
}
