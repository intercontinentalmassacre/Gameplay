package app.gamenative.utils

import androidx.compose.runtime.saveable.SaverScope
import com.winlator.container.ContainerData

/**
 * Classifies [ContainerData] changes made while a game is running.
 *
 * Fields in [liveApplyKeys] are adopted by the running session immediately
 * (input modes, gestures, HUD, screen effects, suspend policy). Everything
 * else (Wine/Proton, DXVK/VKD3D, graphics driver, Box64/FEX, env vars,
 * drives, Windows components, display renderer, ...) only takes effect on
 * the next container boot and therefore requires a game restart.
 */
object ContainerConfigRuntime {

    private val liveApplyKeys = setOf(
        "name",
        "showFPS",
        "suspendPolicy",
        "enableXInput",
        "enableDInput",
        "dinputMapperType",
        "disableMouseInput",
        "touchscreenMode",
        "shooterMode",
        "gestureConfig",
        "shooterConfig",
        "externalDisplayMode",
        "externalDisplaySwap",
        "sharpnessEffect",
        "sharpnessLevel",
        "sharpnessDenoise",
    )

    // Wine-registry fields missing from ContainerData.Saver; compared directly.
    private val unsavedKeys = listOf(
        "renderer",
        "csmt",
        "videoPciDeviceID",
        "offScreenRenderingMode",
        "strictShaderMath",
        "videoMemorySize",
        "shaderBackend",
        "useGLSL",
        "mouseWarpOverride",
    )

    fun changedFieldNames(old: ContainerData, new: ContainerData): Set<String> {
        val oldMap = old.toSaverMap()
        val newMap = new.toSaverMap()
        val saverChanges = (oldMap.keys + newMap.keys)
            .filter { oldMap[it] != newMap[it] }
        val unsavedChanges = unsavedKeys
            .filter { old.unsavedValue(it) != new.unsavedValue(it) }
        return (saverChanges + unsavedChanges).toSet()
    }

    fun restartRequiredChangedFields(old: ContainerData, new: ContainerData): Set<String> =
        changedFieldNames(old, new) - liveApplyKeys

    fun requiresRestart(old: ContainerData, new: ContainerData): Boolean =
        restartRequiredChangedFields(old, new).isNotEmpty()

    private fun ContainerData.unsavedValue(key: String): Any? = when (key) {
        "renderer" -> renderer
        "csmt" -> csmt
        "videoPciDeviceID" -> videoPciDeviceID
        "offScreenRenderingMode" -> offScreenRenderingMode
        "strictShaderMath" -> strictShaderMath
        "videoMemorySize" -> videoMemorySize
        "shaderBackend" -> shaderBackend
        "useGLSL" -> useGLSL
        "mouseWarpOverride" -> mouseWarpOverride
        else -> null
    }

    private fun ContainerData.toSaverMap(): Map<String, Any?> {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        // mapSaver on Android marshals the map into a flat [key, value, ...] list.
        return when (val saved = with(ContainerData.Saver) { scope.save(this@toSaverMap) }) {
            is Map<*, *> -> saved.entries
                .mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
                .toMap()
            is List<*> -> saved.chunked(2)
                .mapNotNull { pair ->
                    val key = pair.getOrNull(0) as? String ?: return@mapNotNull null
                    key to pair.getOrNull(1)
                }
                .toMap()
            else -> emptyMap()
        }
    }
}
