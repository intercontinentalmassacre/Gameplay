package app.gamenative.gamefixes

import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException

private const val CAPTURE_MODULE = "d3dgear64"
private const val CAPTURE_DLL = "$CAPTURE_MODULE.dll"

// D3DGear's bundled capture hook can prevent a game from drawing in Wine.
internal fun d3dGearCaptureOverride(installPath: String, executablePath: String, overrides: String): String? {
    if (installPath.isBlank() || executablePath.isBlank()) return null

    val hasExplicitOverride = overrides.split(';').any { entry ->
        '=' in entry && entry.substringBefore('=').split(',').any { name ->
            val module = name.trim().trimStart('*').replace('\\', '/').substringAfterLast('/')
            module.equals(CAPTURE_MODULE, ignoreCase = true) || module.equals(CAPTURE_DLL, ignoreCase = true)
        }
    }
    if (hasExplicitOverride) return null

    val hasCaptureHook = try {
        val installDir = File(installPath).toPath().toAbsolutePath().normalize()
        val executable = installDir.resolve(executablePath.replace('\\', '/')).normalize()
        val directory = executable.parent ?: return null
        if (!directory.startsWith(installDir)) return null

        Files.isRegularFile(directory.resolve(CAPTURE_DLL)) || Files.newDirectoryStream(directory).use { entries ->
            entries.any { it.fileName.toString().equals(CAPTURE_DLL, ignoreCase = true) && Files.isRegularFile(it) }
        }
    } catch (_: InvalidPathException) {
        false
    } catch (_: IOException) {
        false
    } catch (_: DirectoryIteratorException) {
        false
    }
    if (!hasCaptureHook) return null

    val delimiter = if (overrides.isEmpty() || overrides.endsWith(';')) "" else ";"
    return "$overrides$delimiter$CAPTURE_MODULE=d"
}
