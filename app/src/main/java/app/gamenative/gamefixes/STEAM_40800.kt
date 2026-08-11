package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container

/**
 * Super Meat Boy (Steam, appId 40800)
 *
 * The builtin Wine xaudio2_7 has broken audio for this game (Proton issue
 * #5888). Force the native FAudio component and align the audio driver with
 * the pulseaudio path the container env already assumes.
 */
val STEAM_Fix_40800: KeyedGameFix = object : KeyedGameFix {
    override val gameSource = GameSource.STEAM
    override val gameId = "40800"

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        container.setWinComponents(withWinComponent(container.winComponents, "xaudio2_7", "1"))
        container.audioDriver = "pulseaudio"
        container.saveData()
        return true
    }
}

/** Sets `key=value` inside a wincomponents string ("a=1,b=0,..."), appending when missing. */
internal fun withWinComponent(wincomponents: String, key: String, value: String): String {
    val entries = wincomponents.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toMutableList()
    val index = entries.indexOfFirst { it.substringBefore("=") == key }
    if (index >= 0) {
        entries[index] = "$key=$value"
    } else {
        entries += "$key=$value"
    }
    return entries.joinToString(",")
}
