package app.gamenative.localgames

/**
 * The executable format determines which runtime can be offered before a container is created.
 *
 * This is deliberately about file structure, not a claim that a title is compatible with a
 * particular Wine or Proton configuration.
 */
enum class ExecutableKind {
    WINDOWS_16_NE,
    WINDOWS_32_PE,
    WINDOWS_64_PE,
    WINDOWS_INSTALLER_MSI,
    DOS_ONLY,
    UNKNOWN,
    MALFORMED,
}

data class ExecutableInspection(
    val kind: ExecutableKind,
    val reason: String? = null,
)
