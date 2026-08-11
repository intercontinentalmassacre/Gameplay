package app.gamenative.ui.data

import app.gamenative.localgames.InstallationState

/**
 * Live installation state for a single library card, keyed by [app.gamenative.data.LibraryItem.appId].
 *
 * Two distinct flows feed it:
 * - Store downloads (Steam/Epic/GOG/Amazon) expose a numeric 0..1 fraction via [app.gamenative.data.DownloadInfo].
 * - Local installers (EXE/MSI) advance through [InstallationState] stages with no percentage.
 */
sealed interface InstallProgress {
    /** A store download in progress; [fraction] is 0..1. */
    data class Downloading(val fraction: Float) : InstallProgress

    /** Post-install sync/unpacking after a store download; indeterminate. */
    data object Syncing : InstallProgress

    /** A local installer session is active; the stage is shown as an indeterminate label. */
    data class Installing(val stage: InstallationState) : InstallProgress
}

/** Stages that keep a card in the "installing" state; everything else is terminal or idle. */
private val ACTIVE_INSTALLATION_STATES = setOf(
    InstallationState.SOURCE_STAGED,
    InstallationState.CONTAINER_CREATING,
    InstallationState.READY_TO_LAUNCH,
    InstallationState.INSTALLER_RUNNING,
    InstallationState.AWAITING_RESULT,
    InstallationState.CANDIDATE_SELECTION,
    InstallationState.RESTART_REQUIRED,
    InstallationState.PAUSED,
)

internal val InstallationState.isActiveInstallation: Boolean
    get() = this in ACTIVE_INSTALLATION_STATES
