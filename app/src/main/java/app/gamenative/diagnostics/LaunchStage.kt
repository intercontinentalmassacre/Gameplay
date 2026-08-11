package app.gamenative.diagnostics

/** Lifecycle stages of a single game launch, per docs/PRODUCTION_PLAN.md stage 6.1. */
enum class LaunchStage {
    REQUEST_CREATED,
    DEVICE_PROBED,
    BUNDLE_RESOLVED,
    COMPONENT_CHECK,
    COMPONENT_DOWNLOAD,
    COMPONENT_VERIFY,
    COMPONENT_EXTRACT,
    PREFIX_CHECK,
    PREFIX_MIGRATION,
    PRELAUNCH_SETUP,
    CPU_BACKEND_INIT,
    WINE_BOOT,
    GRAPHICS_INIT,
    GAME_PROCESS_CREATED,
    FIRST_WINDOW,
    FIRST_SURFACE,
    RUNNING,
    EXITING,
    EXITED,
    FAILED,
    CANCELLED,
    ;

    companion object {
        /** Tolerant mapping for legacy string stage labels persisted by earlier builds. */
        fun fromLegacy(label: String): LaunchStage? = when (label.lowercase()) {
            "launch_requested", "request_created" -> REQUEST_CREATED
            "preparing_launch", "preparing_runtime" -> PRELAUNCH_SETUP
            "launching_game" -> CPU_BACKEND_INIT
            "running" -> RUNNING
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> null
        }
    }
}
