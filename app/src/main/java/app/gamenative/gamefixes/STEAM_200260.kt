package app.gamenative.gamefixes

import app.gamenative.data.GameSource

// Batman: Arkham City GOTY needs full x87 precision under FEX.
val STEAM_Fix_200260: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "200260",
    envVarsToSet = mapOf("FEX_X87REDUCEDPRECISION" to "0"),
)
