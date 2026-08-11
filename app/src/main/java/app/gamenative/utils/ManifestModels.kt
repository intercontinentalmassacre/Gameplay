package app.gamenative.utils

import kotlinx.serialization.Serializable

@Serializable
data class ManifestEntry(
    val id: String,
    val name: String,
    val url: String,
    val variant: String? = null,
    val arch: String? = null,
    val version: String? = null,
    val channel: String? = null,
    val abi: List<String> = emptyList(),
    val archiveFormat: String? = null,
    val urls: List<String> = emptyList(),
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val sourceRepository: String? = null,
    val sourceCommit: String? = null,
    val license: String? = null,
    val pageSizes: List<Int> = emptyList(),
    val requiredFiles: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

@Serializable
data class ManifestData(
    val version: Int?,
    val updatedAt: String?,
    val items: Map<String, List<ManifestEntry>>,
    val schemaVersion: Int = 1,
    val catalogVersion: String? = null,
    val generatedAt: String? = null,
) {
    companion object {
        fun empty(): ManifestData = ManifestData(null, null, emptyMap())
    }
}

object ManifestContentTypes {
    const val DRIVER = "driver"
    const val DXVK = "dxvk"
    const val VKD3D = "vkd3d"
    const val BOX64 = "box64"
    const val WOWBOX64 = "wowbox64"
    const val FEXCORE = "fexcore"
    const val WINE = "wine"
    const val PROTON = "proton"

    val ALL = setOf(DRIVER, DXVK, VKD3D, BOX64, WOWBOX64, FEXCORE, WINE, PROTON)
}
