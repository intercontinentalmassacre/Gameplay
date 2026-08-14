package app.gamenative.drivers

import android.content.Context
import com.winlator.core.GPUInformation

/**
 * Top-level grouping of trusted driver sources. Used by the driver manager UI
 * to bucket repositories so the user is not staring at a flat list of GitHub
 * release feeds with no signal about which to pick for which GPU.
 */
enum class DriverCategory(val sortOrder: Int) {
    /** Vendored stable Mesa Turnip releases — the safest baseline. */
    TURNIP_STABLE(0),

    /** Latest Mesa commits, rebuilt on every push. Bleeding edge, may regress. */
    TURNIP_BLEEDING_EDGE(1),

    /** Community-maintained Turnip forks and one-off builds. */
    TURNIP_COMMUNITY(2),

    /** Generic AdrenoTools-compatible Qualcomm packages (not necessarily Turnip). */
    ADRENO_PACKAGED(3),

    /** Bionic Vulkan wrapper (Mesa Vulkan ICD + adrenotools hooks). */
    WRAPPER(4),
}

/**
 * Which Adreno GPU generations a driver package is expected to work on.
 * Used for informational UI hints; runtime filtering still happens at install.
 */
enum class AdrenoTarget(val displayKey: String) {
    /** Adreno 600-series (Snapdragon 600-700 era). */
    A6XX("adreno_target_a6xx"),

    /** Adreno 700-series (Snapdragon 7 Gen 1+). */
    A7XX("adreno_target_a7xx"),

    /** Adreno 710/720/722 — experimental, no upstream Mesa support yet. */
    A710_A720_A722("adreno_target_a710_a720_a722"),

    /** Adreno 800-series (Snapdragon 8 Elite: A810, A825, A829, A830). */
    A8XX("adreno_target_a8xx"),

    /** Cross-generation / vendor-neutral packages. */
    GENERIC("adreno_target_generic"),
}

/**
 * How the repository packages its assets. [GetDriverDialog] uses this to
 * show install hints; the actual install path lives in AdrenotoolsManager.
 */
enum class DriverPackagingFormat {
    /** AdrenoTools-compatible ZIP (turnip binaries + metadata). */
    ADRENOTOOLS_ZIP,

    /** bionic-vulkan-wrapper tarball (libvulkan_wrapper.so + ICD json). */
    WRAPPER_TARBALL,

    /** Mixed/unknown — surfaced as raw asset listing. */
    UNKNOWN,
}

/**
 * Detect this device's Adreno generation so the dialog can pre-select a
 * sensible category and show a one-line "Detected: Adreno X" hint. Returns
 * [AdrenoTarget.GENERIC] for non-Adreno GPUs (Mali, Xclipse, PowerVR) so the
 * caller falls back to the GENERIC-targeted repositories.
 *
 * The detection reuses [GPUInformation]'s regex helpers and only adds an
 * A7XX-specific matcher for the gap between A6xx and A8xx detection.
 */
fun detectAdrenoTarget(context: Context): AdrenoTarget {
    val renderer = GPUInformation.getRenderer(context).lowercase()
    if (!renderer.contains("adreno")) return AdrenoTarget.GENERIC

    return when {
        // A8xx first — most specific (830-859).
        GPUInformation.isAdreno8Elite(context) -> AdrenoTarget.A8XX

        // A710/720/722 — known problematic subset.
        GPUInformation.isAdreno710_720_732(context) -> AdrenoTarget.A710_A720_A722

        // A740 — 7-series but a known-good target.
        GPUInformation.isAdreno740(context) -> AdrenoTarget.A7XX

        // Remaining A6xx.
        GPUInformation.isAdreno6xx(context) -> AdrenoTarget.A6XX

        // Anything else Adreno (e.g. A12 alphanumeric, unreleased parts) — treat
        // as 7xx-ish; Mesa Turnip will accept the driver either way.
        else -> AdrenoTarget.A7XX
    }
}

/**
 * Pick the [DriverCategory] most likely to be useful for [target]. Used by the
 * dialog to pre-select on open; the user can still override. Returns the
 * first non-empty category if the GPU-aware pick has no repos (e.g. the
 * device is non-Adreno and only GENERIC repos apply).
 */
fun preferredCategoryFor(target: AdrenoTarget): DriverCategory = when (target) {
    AdrenoTarget.A8XX,
    AdrenoTarget.A710_A720_A722 -> DriverCategory.TURNIP_BLEEDING_EDGE

    AdrenoTarget.A6XX,
    AdrenoTarget.A7XX -> DriverCategory.TURNIP_STABLE

    AdrenoTarget.GENERIC -> DriverCategory.ADRENO_PACKAGED
}

