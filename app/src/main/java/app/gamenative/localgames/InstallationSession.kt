package app.gamenative.localgames

enum class InstallerType {
    EXE,
    MSI,
}

enum class InstallationState {
    SOURCE_STAGED,
    CONTAINER_CREATING,
    READY_TO_LAUNCH,
    INSTALLER_RUNNING,
    AWAITING_RESULT,
    CANDIDATE_SELECTION,
    RESTART_REQUIRED,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class InstallationSession(
    val id: String,
    val title: String,
    val sourceUri: String,
    val sourceName: String,
    val installerType: InstallerType,
    val managedInstallerPath: String,
    val installerRelativePath: String,
    val state: InstallationState,
    val previousState: InstallationState? = null,
    val appId: String? = null,
    val containerId: String? = null,
    val selectedExecutablePath: String? = null,
    val candidateExecutablePaths: List<String> = emptyList(),
    val baselineExecutablePaths: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
) {
    fun transitionTo(
        next: InstallationState,
        now: Long = System.currentTimeMillis(),
        error: String? = null,
    ): InstallationSession {
        require(InstallationStateMachine.canTransition(state, next)) {
            "Invalid installation transition: $state -> $next"
        }
        return copy(
            state = next,
            previousState = state,
            updatedAt = now,
            lastError = error,
        )
    }
}

object InstallationStateMachine {
    private val transitions = mapOf(
        InstallationState.SOURCE_STAGED to setOf(
            InstallationState.CONTAINER_CREATING,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.CONTAINER_CREATING to setOf(
            InstallationState.READY_TO_LAUNCH,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.READY_TO_LAUNCH to setOf(
            InstallationState.INSTALLER_RUNNING,
            InstallationState.PAUSED,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.INSTALLER_RUNNING to setOf(
            InstallationState.AWAITING_RESULT,
            InstallationState.RESTART_REQUIRED,
            InstallationState.PAUSED,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.AWAITING_RESULT to setOf(
            InstallationState.CANDIDATE_SELECTION,
            InstallationState.READY_TO_LAUNCH,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.CANDIDATE_SELECTION to setOf(
            InstallationState.COMPLETED,
            InstallationState.READY_TO_LAUNCH,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.RESTART_REQUIRED to setOf(
            InstallationState.READY_TO_LAUNCH,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.PAUSED to setOf(
            InstallationState.READY_TO_LAUNCH,
            InstallationState.FAILED,
            InstallationState.CANCELLED,
        ),
        InstallationState.FAILED to setOf(
            InstallationState.READY_TO_LAUNCH,
            InstallationState.CANDIDATE_SELECTION,
            InstallationState.CANCELLED,
        ),
        InstallationState.COMPLETED to emptySet(),
        InstallationState.CANCELLED to emptySet(),
    )

    fun canTransition(from: InstallationState, to: InstallationState): Boolean =
        to in transitions.getValue(from)
}
