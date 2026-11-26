package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import com.winlator.core.KeyValueSet

// GameNative's Half-Life 2 launch configuration.
val STEAM_Fix_220: KeyedGameFix = KeyedLaunchArgFix(GameSource.STEAM, "220", "-game hl2_complete")

val STEAM_Fix_658920: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "658920" // Half-Life 2: VR Mod

    override fun apply(context: Context, gameId: String, installPath: String, installPathWindows: String, container: Container): Boolean {
        val config = KeyValueSet(container.graphicsDriverConfig)
        val existing = config.get("blacklistedExtensions")
        val updated = appendVulkanBlacklist(existing, "VK_EXT_fragment_density_map")
        if (updated != existing) {
            config.put("blacklistedExtensions", updated)
            container.graphicsDriverConfig = config.toString()
            container.saveData()
        }
        return true
    }
}

internal fun appendVulkanBlacklist(existing: String, extension: String): String {
    val entries = existing.split('|', ',').map(String::trim).filter(String::isNotEmpty)
    return if (extension in entries) existing else (entries + extension).joinToString("|")
}
