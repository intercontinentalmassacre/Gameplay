package app.gamenative.ui.util

import android.content.Context
import com.winlator.container.ContainerData
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A per-game bookmark of tunable settings, stored independently of the container and its files. */
internal class WorkingContainerConfig(context: Context, private val appId: String) {
    private val preferences = context.getSharedPreferences("working_container_config", Context.MODE_PRIVATE)

    fun load(): ContainerData? {
        val stored = preferences.getString(appId, null) ?: return null
        return try {
            format.decodeFromString(ContainerData.serializer(), stored)
        } catch (_: SerializationException) {
            null
        }
    }

    fun save(config: ContainerData): Boolean {
        val bookmark = ContainerData(
            containerVariant = config.containerVariant,
            wineVersion = config.wineVersion,
        ).withWorkingSettings(config)
        return preferences.edit()
            .putString(appId, format.encodeToString(ContainerData.serializer(), bookmark))
            .commit()
    }

    private companion object {
        val format = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

// Leave launch targets, mounted paths, login/save policy and Wine prefix selection in the draft.
internal fun ContainerData.withWorkingSettings(bookmark: ContainerData): ContainerData = copy(
    screenSize = bookmark.screenSize,
    graphicsDriver = bookmark.graphicsDriver,
    graphicsDriverVersion = bookmark.graphicsDriverVersion,
    graphicsDriverConfig = bookmark.graphicsDriverConfig,
    rendererPresentMode = bookmark.rendererPresentMode,
    displayRenderer = bookmark.displayRenderer,
    displayServer = bookmark.displayServer,
    sfCompatMode = bookmark.sfCompatMode,
    dxwrapper = bookmark.dxwrapper,
    dxwrapperConfig = bookmark.dxwrapperConfig,
    audioDriver = bookmark.audioDriver,
    pulseaudioLowLatency = bookmark.pulseaudioLowLatency,
    gnaudioLowLatency = bookmark.gnaudioLowLatency,
    wincomponents = bookmark.wincomponents,
    cpuList = bookmark.cpuList,
    cpuListWoW64 = bookmark.cpuListWoW64,
    startupSelection = bookmark.startupSelection,
    box64Version = bookmark.box64Version,
    box64Preset = bookmark.box64Preset,
    fexcoreVersion = bookmark.fexcoreVersion,
    fexcoreTSOMode = bookmark.fexcoreTSOMode,
    fexcoreX87Mode = bookmark.fexcoreX87Mode,
    fexcoreMultiBlock = bookmark.fexcoreMultiBlock,
    fexcorePreset = bookmark.fexcorePreset,
    renderer = bookmark.renderer,
    csmt = bookmark.csmt,
    videoPciDeviceID = bookmark.videoPciDeviceID,
    offScreenRenderingMode = bookmark.offScreenRenderingMode,
    strictShaderMath = bookmark.strictShaderMath,
    useDRI3 = bookmark.useDRI3,
    hdrEnabled = bookmark.hdrEnabled,
    gamescopeOutputResolution = bookmark.gamescopeOutputResolution,
    gamescopeExperimental = bookmark.gamescopeExperimental,
    fossilizeEnabled = bookmark.fossilizeEnabled,
    videoMemorySize = bookmark.videoMemorySize,
    shaderBackend = bookmark.shaderBackend,
    useGLSL = bookmark.useGLSL,
    sharpnessEffect = bookmark.sharpnessEffect,
    sharpnessLevel = bookmark.sharpnessLevel,
    sharpnessDenoise = bookmark.sharpnessDenoise,
    lsfgEnabled = bookmark.lsfgEnabled,
)
