package app.gamenative.shadercache

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import com.winlator.container.ContainerManager
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.function.BooleanSupplier

/** Embedded Buran calls this from its render process, after selecting the game's actual Vulkan ICD. */
@Keep
object BuranFossilizeBridge {
    private val replayVariables = setOf(
        "ANDROID_ROOT", "LANG", "LD_LIBRARY_PATH", "VK_DRIVER_FILES", "VK_ICD_FILENAMES",
        "VK_ADD_IMPLICIT_LAYER_PATH", "XDG_CACHE_HOME", "TU_DEBUG", "GALLIUM_DRIVER", "LIBGL_KOPPER_DISABLE",
        "GN_VKSHIM_SYSTEM_DRIVER",
    )
    private val captureVariables = setOf(
        "FOSSILIZE_DUMP_PATH", "FOSSILIZE_DUMP_PATH_READ_ONLY",
        "FOSSILIZE_APPLICATION_INFO_FILTER_PATH", "VK_ADD_IMPLICIT_LAYER_PATH",
    )

    @JvmStatic
    fun prepare(context: Context, installationKey: String, finalGuestEnv: Map<String, String>,
                cancelled: BooleanSupplier, onReplay: Runnable): Map<String, String> {
        if (cancelled.asBoolean) return emptyMap()
        val cachePath = finalGuestEnv["MESA_SHADER_CACHE_DIR"] ?: return emptyMap()
        val disabled = finalGuestEnv["MESA_SHADER_CACHE_DISABLE"]
        val files = context.filesDir.canonicalFile.toPath()
        val cache = File(cachePath).canonicalFile
        if (!cache.isDirectory || !cache.toPath().startsWith(files) ||
            disabled == "1" || disabled.equals("true", true)) return emptyMap()
        val appId = gameplayContainerId(context.packageName, installationKey) ?: return emptyMap()
        val container = ContainerManager(context).getContainerById(appId) ?: return emptyMap()
        if (!container.getExtra(FossilizeManager.EXTRA_ENABLED).toBoolean()) return emptyMap()

        val env = EnvVars()
        for ((name, value) in finalGuestEnv) {
            if (name in replayVariables || name.startsWith("ADRENOTOOLS_") ||
                name.startsWith("WRAPPER_") || name.startsWith("MESA_")) {
                env.put(name, value)
            }
        }
        env.put("HOME", cache.parentFile)
        if (!FossilizeManager.prepareEmbedded(context, container, env, cancelled, onReplay)) return emptyMap()
        return buildMap {
            for (name in captureVariables) if (env.has(name)) put(name, env.get(name))
            put("FOSSILIZE", "1")
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
                File(context.cacheDir, "fossilize-gpl-trace").isFile
            ) {
                // A traced session needs its own complete record to correlate live handles
                // with serialized pipelines, even when the same hashes exist in older archives.
                remove("FOSSILIZE_DUMP_PATH_READ_ONLY")
                put("FOSSILIZE_TRACE_GRAPHICS", "1")
            }
        }
    }

    // Buran namespaces its installation key with the calling package. Gameplay stores the bare ID.
    internal fun gameplayContainerId(packageName: String, installationKey: String): String? {
        val prefix = "$packageName:"
        if (!installationKey.startsWith(prefix)) return null
        return installationKey.substring(prefix.length).takeIf(String::isNotEmpty)
    }
}
