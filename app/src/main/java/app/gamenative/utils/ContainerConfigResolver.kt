package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import org.json.JSONObject
import java.util.Locale

/**
 * Centralizes container configuration decisions shared between the container
 * editor and best-config application:
 *
 * - GPU-family hard constraints (Adreno 6xx / 8 Elite Gen5 / A12 driver and
 *   DXVK picks) so the editor and the server config path agree on one set of
 *   rules.
 * - Default VKD3D version selection from the versions that are actually
 *   available on device instead of hardcoded strings.
 */
object ContainerConfigResolver {
    /** Drivers whose renderers do not support the newest VKD3D releases. */
    private val vortekLikeDrivers = setOf("vortek", "adreno", "sd-8-elite")

    fun isVortekLike(containerVariant: String, driverType: String): Boolean {
        return containerVariant.equals(Container.GLIBC, ignoreCase = true) &&
            driverType in vortekLikeDrivers
    }

    /**
     * Applies hard GPU-family constraints that override server-provided versions
     * when the matched GPU is from a different family than the user's actual GPU.
     *
     * - Adreno 6xx requires DXVK 1.11.1-sarek (newer DXVK 2.x is incompatible).
     * - Adreno 8 Elite Gen5 (84x/85x) requires the MrPurple T26 driver.
     * - Adreno A12 requires the A12-fix Turnip driver.
     *
     * The override is skipped when the matched GPU is the same family (e.g. an
     * exact A12 match), so a server-provided exact-GPU config is left untouched.
     */
    fun applyGpuFamilyOverrides(
        context: Context,
        filteredJson: JSONObject,
        matchedGpu: String,
    ): JSONObject {
        if (matchedGpu.isEmpty()) return filteredJson
        val matched = matchedGpu.lowercase(Locale.ENGLISH)

        if (GPUInformation.isAdreno6xx(context) && !matched.matches(Regex(".*adreno.*\\b6[0-9]{2}\\b.*"))) {
            val kvs = KeyValueSet(filteredJson.optString("dxwrapperConfig", ""))
            kvs.put("version", "1.11.1-sarek")
            filteredJson.put("dxwrapper", "dxvk")
            filteredJson.put("dxwrapperConfig", kvs.toString())
        }

        if (GPUInformation.isAdreno8EliteGen5(context) &&
            !matched.matches(Regex(".*adreno.*\\b8(4[0-9]|5[0-9])\\b.*"))
        ) {
            val kvs = KeyValueSet(filteredJson.optString("graphicsDriverConfig", ""))
            kvs.put("version", ContainerUtils.WRAPPER_ADRENO_8ELITE_GEN5)
            filteredJson.put("graphicsDriverConfig", kvs.toString())
            filteredJson.put("graphicsDriverVersion", ContainerUtils.WRAPPER_ADRENO_8ELITE_GEN5)
        }

        if (GPUInformation.isAdreno8Elite(context) &&
            !GPUInformation.isAdreno8EliteGen5(context) &&
            !matched.matches(Regex(".*adreno.*\\b83[0-9]\\b.*"))
        ) {
            val kvs = KeyValueSet(filteredJson.optString("graphicsDriverConfig", ""))
            kvs.put("version", ContainerUtils.WRAPPER_ADRENO_8ELITE)
            filteredJson.put("graphicsDriverConfig", kvs.toString())
            filteredJson.put("graphicsDriverVersion", ContainerUtils.WRAPPER_ADRENO_8ELITE)
        }

        if (GPUInformation.isAdrenoA12(context) && !matched.matches(Regex(".*adreno.*\\ba12\\b.*"))) {
            val kvs = KeyValueSet(filteredJson.optString("graphicsDriverConfig", ""))
            kvs.put("version", ContainerUtils.WRAPPER_ADRENO_A12)
            filteredJson.put("graphicsDriverConfig", kvs.toString())
            filteredJson.put("graphicsDriverVersion", ContainerUtils.WRAPPER_ADRENO_A12)
        }

        if (BuildConfig.XR_BUILD) {
            val kvs = KeyValueSet(filteredJson.optString("graphicsDriverConfig", ""))
            val isTurnip = filteredJson.optString("graphicsDriverVersion", "").contains("turnip", ignoreCase = true) ||
                kvs.get("version").contains("turnip", ignoreCase = true)
            if (isTurnip) {
                kvs.put("adrenotoolsTurnip", "0")
                filteredJson.put("graphicsDriverConfig", kvs.toString())
            }
        }

        return filteredJson
    }

    /**
     * Picks the default VKD3D version for the given container/driver pair.
     *
     * Prefers the highest available version already on device (or bundled); the
     * manifest-only entries (not yet installed) are skipped so the chosen default
     * does not silently trigger a download. Vortek-like drivers are capped at a
     * version their renderer supports. Falls back to the legacy hardcoded values
     * when nothing usable is available.
     */
    fun defaultVkd3dVersion(
        containerVariant: String,
        driverType: String,
        availableIds: List<String>,
        muted: List<Boolean>?,
    ): String {
        val usable = availableIds.filterIndexed { index, _ -> muted?.getOrNull(index) != true }
        val vortek = isVortekLike(containerVariant, driverType)

        val candidates = usable.mapNotNull { id ->
            ManifestComponentHelper.parseSemVerTriplet(id)?.let { triplet -> id to triplet }
        }
        val withinCap = candidates
            .filter { (id, _) -> !vortek || !ManifestComponentHelper.isAtLeastVersion(id, 2, 7, 0) }
        val best = withinCap.maxByOrNull { (_, triplet) -> triplet.first * 10000 + triplet.second * 100 + triplet.third }

        return best?.first ?: if (vortek) "2.6" else "2.14.1"
    }
}
