package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamUtilsColdClientIniTest {
    @Test
    fun `Dragon's Dogma 2 receives the compatibility option in ColdClient`() {
        val config = SteamUtils.resolveColdClientLaunchConfig(
            steamAppId = 2054970,
            executablePath = "DD2.exe",
            exeCommandLine = "-windowed",
            gameRootDir = File("."),
        )
        assertEquals("-windowed /WineDetectionEnabled:False", config.exeCommandLine)
    }

    private fun generate(
        gameName: String = "Batman Arkham Asylum GOTY",
        executablePath: String = "Binaries\\BmLauncher.exe",
        exeCommandLine: String = "",
        steamAppId: Int = 35140,
        workingDir: String? = null,
        isUnpackFiles: Boolean = false,
    ) = SteamUtils.generateColdClientIni(
        gameName = gameName,
        executablePath = executablePath,
        exeCommandLine = exeCommandLine,
        steamAppId = steamAppId,
        workingDir = workingDir,
        isUnpackFiles = isUnpackFiles,
    )

    private fun String.iniValue(key: String): String =
        lines().first { it.startsWith("$key=") }.removePrefix("$key=")

    @Test
    fun `ExeRunDir is exe directory when no workingDir`() {
        val ini = generate(gameName = "New Star GP", executablePath = "release/NSGP.exe", workingDir = null)
        assertEquals("steamapps\\common\\New Star GP\\release", ini.iniValue("ExeRunDir"))
    }

    @Test
    fun `ExeRunDir is blank when workingDir is set`() {
        // workingDir set: leave blank (legacy behaviour, same as master)
        val ini = generate(workingDir = "Binaries")
        assertEquals("", ini.iniValue("ExeRunDir"))
    }

    @Test
    fun `ExeRunDir is exe directory when workingDir is empty string`() {
        val ini = generate(gameName = "New Star GP", executablePath = "release/NSGP.exe", workingDir = "")
        assertEquals("steamapps\\common\\New Star GP\\release", ini.iniValue("ExeRunDir"))
    }

}
