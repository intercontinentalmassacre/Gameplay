package app.gamenative.localgames

import java.io.File
import java.io.FileInputStream

/** Finds likely game launchers created inside a Wine C: drive by a local installer. */
object InstalledExecutableScanner {
    private const val MAX_SCAN_DEPTH = 10

    private val preferredRoots = listOf(
        "Program Files",
        "Program Files (x86)",
        "Games",
        "GOG Games",
    )

    private val rejectedNameFragments = listOf(
        "unins",
        "uninstall",
        "setup",
        "installer",
        "installshield",
        "update",
        "updater",
        "patcher",
        "crashreport",
        "crashhandler",
        "dxsetup",
        "vcredist",
        "vc_redist",
        "dotnet",
    )

    fun findCandidates(driveC: File): List<String> = scan(driveC, applyNameFilter = true)

    /**
     * Recovery variant: every executable on drive C: without the
     * uninstaller/updater name filtering, for manual selection when the
     * filtered discovery finds nothing.
     */
    fun findAllExecutables(driveC: File): List<String> = scan(driveC, applyNameFilter = false)

    private fun scan(driveC: File, applyNameFilter: Boolean): List<String> {
        if (!driveC.isDirectory) return emptyList()

        val roots = buildList {
            preferredRoots.mapTo(this) { File(driveC, it) }
            add(driveC)
        }.filter(File::isDirectory)

        return roots.asSequence()
            .flatMap { root ->
                root.walkTopDown()
                    .maxDepth(MAX_SCAN_DEPTH)
                    .onEnter { directory -> shouldEnterDirectory(driveC, directory) }
                    .filter { file -> isCandidate(file, applyNameFilter) }
            }
            .mapNotNull { executable ->
                executable.relativeToOrNull(driveC)
                    ?.invariantSeparatorsPath
                    ?.takeIf(::isSafeRelativePath)
            }
            .distinctBy(String::lowercase)
            .sortedWith(compareBy<String>({ candidateScore(it) }, { it.lowercase() }))
            .toList()
    }

    private fun shouldEnterDirectory(driveC: File, directory: File): Boolean {
        if (directory == driveC) return true
        val relative = directory.relativeToOrNull(driveC)?.invariantSeparatorsPath.orEmpty()
        val firstSegment = relative.substringBefore('/').lowercase()
        return firstSegment !in setOf("windows", "users", "programdata", "temp", "tmp", "\$recycle.bin")
    }

    private fun isCandidate(file: File, applyNameFilter: Boolean): Boolean {
        if (!file.isFile || !file.name.endsWith(".exe", ignoreCase = true)) return false
        val normalizedName = file.nameWithoutExtension.lowercase()
        if (applyNameFilter && rejectedNameFragments.any(normalizedName::contains)) return false

        return isWindowsExecutable(file)
    }

    private fun isWindowsExecutable(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            when (WindowsExecutableInspector.inspect(input).kind) {
                ExecutableKind.WINDOWS_16_NE,
                ExecutableKind.WINDOWS_32_PE,
                ExecutableKind.WINDOWS_64_PE -> true
                else -> false
            }
        }
    }.getOrDefault(false)

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            path.split('/').none { it == ".." }

    private fun candidateScore(path: String): Int {
        val normalized = path.lowercase()
        return when {
            "shipping.exe" in normalized -> 0
            "/bin/" in normalized || "/binaries/" in normalized -> 1
            normalized.startsWith("program files/") || normalized.startsWith("program files (x86)/") -> 2
            normalized.startsWith("games/") || normalized.startsWith("gog games/") -> 3
            else -> 4
        }
    }
}
