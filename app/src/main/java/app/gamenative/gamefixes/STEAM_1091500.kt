package app.gamenative.gamefixes

import java.io.File

internal const val CYBERPUNK_2077_APP_ID = 1091500

// Prefer the game binary over REDprelauncher.exe during automatic selection.
internal fun installedCyberpunk2077Exe(installPath: String): String? {
    if (installPath.isBlank()) return null
    val executable = "bin/x64/Cyberpunk2077.exe"
    return executable.takeIf { File(installPath, it).isFile }
}
