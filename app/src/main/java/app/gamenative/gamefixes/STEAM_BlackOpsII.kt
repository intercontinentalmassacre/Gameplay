package app.gamenative.gamefixes

import app.gamenative.data.GameSource

// Campaign, multiplayer and Zombies each have their own Steam app ID.
internal val blackOpsIISteamFixes = listOf("202970", "202990", "212910").map { appId ->
    KeyedWineEnvVarFix(GameSource.STEAM, appId, mapOf("WINESTEAMNOEXEC" to "0"))
}
