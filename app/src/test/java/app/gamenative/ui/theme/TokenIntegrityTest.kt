package app.gamenative.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "theme tokens only" rule: no `Color(0x…)` literals may appear in
 * composables or anywhere outside the theme package. New UI colors must go
 * through [PluviaTheme.colors] (or the GCDS document) instead of hand-written
 * hex constants, so every built-in theme stays visually coherent.
 */
class TokenIntegrityTest {

    private fun locateMainJavaDir(): File {
        val workingDir = File(System.getProperty("user.dir"))
        val direct = File(workingDir, "app/src/main/java")
        if (direct.exists()) return direct
        val parent = workingDir.parentFile
        if (parent != null) {
            val parentFile = File(parent, "app/src/main/java")
            if (parentFile.exists()) return parentFile
        }
        throw IllegalStateException("app/src/main/java not found from ${workingDir.absolutePath}")
    }

    private val hexColor = Regex("Color\\(0x[0-9a-fA-F]{6,}\\)")

    @Test
    fun noHardcodedColorsOutsideThemePackage() {
        val root = locateMainJavaDir()
        val offenders = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.name.endsWith(".kt.html") }
            .filter { !it.path.contains("ui" + File.separator + "theme" + File.separator) }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (hexColor.containsMatchIn(line)) {
                        offenders += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        assertTrue(
            "Hardcoded Color(0x...) found outside ui/theme/. Route these through theme tokens:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
