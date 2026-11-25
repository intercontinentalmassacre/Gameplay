package app.gamenative.gamefixes

private const val WINE_DETECTION_OPTION = "/WineDetectionEnabled:"
private val launchArgument = Regex("""(?:[^\s"]+|"[^"]*")+""")

// The game's Wine-specific startup path fails. Respect an explicit choice in the launch options.
internal fun withDragonsDogma2LaunchFix(steamAppId: Int, args: String): String {
    if (steamAppId != 2054970) return args

    val hasExplicitOption = launchArgument.findAll(args).any {
        it.value.trimStart('"').startsWith(WINE_DETECTION_OPTION, ignoreCase = true)
    }
    if (hasExplicitOption) return args

    val fix = WINE_DETECTION_OPTION + "False"
    return if (args.isBlank()) fix else "$args $fix"
}
