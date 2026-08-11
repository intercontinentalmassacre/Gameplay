package app.gamenative.utils

import kotlinx.serialization.Serializable

/**
 * Versioned component catalog received from a mutable remote source.
 *
 * V2 deliberately keeps the install type compatible with [ManifestContentTypes]. The runtime
 * bundle model can later compose these low-level components without making the installer guess
 * a component's destination from its id or display name.
 */
@Serializable
data class ComponentCatalogDocument(
    val schemaVersion: Int,
    val catalogVersion: String,
    val generatedAt: String,
    val components: List<ComponentCatalogEntry>,
)

@Serializable
data class ComponentCatalogEntry(
    val id: String,
    val type: String,
    val name: String,
    val version: String,
    val channel: String,
    val variant: String? = null,
    val abi: List<String>,
    val archiveFormat: String,
    val urls: List<String>,
    val sizeBytes: Long,
    val sha256: String,
    val sourceRepository: String,
    val sourceCommit: String,
    val license: String,
    val pageSizes: List<Int>,
    val requiredFiles: List<String>,
    val requires: List<String>,
    val conflicts: List<String>,
)

internal fun ComponentCatalogDocument.toManifestData(): ManifestData {
    val grouped = components.groupBy(ComponentCatalogEntry::type).mapValues { (_, entries) ->
        entries.map { component ->
            ManifestEntry(
                id = component.id,
                name = component.name,
                url = component.urls.first(),
                variant = component.variant,
                version = component.version,
                channel = component.channel,
                abi = component.abi,
                archiveFormat = component.archiveFormat,
                urls = component.urls,
                sizeBytes = component.sizeBytes,
                sha256 = component.sha256,
                sourceRepository = component.sourceRepository,
                sourceCommit = component.sourceCommit,
                license = component.license,
                pageSizes = component.pageSizes,
                requiredFiles = component.requiredFiles,
                requires = component.requires,
                conflicts = component.conflicts,
            )
        }
    }
    return ManifestData(
        version = null,
        updatedAt = generatedAt,
        items = grouped,
        schemaVersion = schemaVersion,
        catalogVersion = catalogVersion,
        generatedAt = generatedAt,
    )
}
