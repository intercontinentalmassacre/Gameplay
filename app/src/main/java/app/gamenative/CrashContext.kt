package app.gamenative

import app.gamenative.diagnostics.LaunchTrace

/** Launch metadata captured without retaining paths, credentials, or other user secrets. */
data class CrashContextSnapshot(
    val appId: String? = null,
    val gameName: String? = null,
    val containerId: String? = null,
    val runtime: String? = null,
    val gpu: String? = null,
    val launchStage: String = "idle",
)

object CrashContext {
    @Volatile
    private var current = CrashContextSnapshot()

    fun beginLaunch(appId: String) {
        current = CrashContextSnapshot(appId = appId, launchStage = "launch_requested")
        LaunchTrace.begin()
    }

    fun update(
        gameName: String? = current.gameName,
        containerId: String? = current.containerId,
        runtime: String? = current.runtime,
        gpu: String? = current.gpu,
        launchStage: String = current.launchStage,
    ) {
        current = current.copy(
            gameName = gameName,
            containerId = containerId,
            runtime = runtime,
            gpu = gpu,
            launchStage = launchStage,
        )
    }

    fun setStage(stage: String) {
        current = current.copy(launchStage = stage)
        LaunchTrace.stageLegacy(stage)
    }

    fun snapshot(): CrashContextSnapshot = current

    fun clear() {
        current = CrashContextSnapshot()
        LaunchTrace.finish("cancelled")
    }

    fun clearIfMatches(appId: String) {
        if (current.appId == appId) {
            current = CrashContextSnapshot()
            LaunchTrace.finish("cancelled")
        }
    }
}
