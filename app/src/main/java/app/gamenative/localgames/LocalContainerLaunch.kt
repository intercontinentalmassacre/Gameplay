package app.gamenative.localgames

object LocalContainerLaunch {
    const val EXTRA_MODE = "localLaunchMode"
    const val EXTRA_TARGET = "localLaunchTarget"
    const val EXTRA_INSTALLATION_SESSION_ID = "localInstallationSessionId"

    /** JSON array of launch arguments, e.g. from a `.itch.toml` play action. */
    const val EXTRA_LAUNCH_ARGS = "localLaunchArgs"

    const val MODE_INSTALLER_EXE_A = "installer-exe-a"
    const val MODE_INSTALLER_MSI_A = "installer-msi-a"
    const val MODE_INSTALLED_EXE_C = "installed-exe-c"
}
