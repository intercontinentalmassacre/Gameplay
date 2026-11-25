package app.gamenative.gamefixes

import app.gamenative.data.GameSource

// Let Teardown control its OpenGL swap interval.
val STEAM_Fix_1167630: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "1167630",
    envVarsToSet = mapOf("vblank_mode" to "2"),
)
