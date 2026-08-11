package app.gamenative.utils

import java.time.Instant

object ComponentCatalogValidator {
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val SOURCE_COMMIT = Regex("^[0-9a-fA-F]{7,64}$")
    private val CHANNELS = setOf("stable", "beta", "experimental")
    private val PAGE_SIZES = setOf(4096, 16384)

    fun validate(document: ComponentCatalogDocument): List<String> {
        val errors = mutableListOf<String>()
        if (document.schemaVersion != 2) errors += "schemaVersion must be 2"
        if (document.catalogVersion.isBlank()) errors += "catalogVersion must not be blank"
        if (runCatching { Instant.parse(document.generatedAt) }.isFailure) {
            errors += "generatedAt must be an ISO-8601 instant"
        }
        if (document.components.isEmpty()) errors += "components must not be empty"

        val duplicateIds = document.components.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        duplicateIds.sorted().forEach { errors += "duplicate component id: $it" }
        val knownIds = document.components.mapTo(mutableSetOf()) { it.id }
        val channelById = document.components.associate { it.id to it.channel }

        document.components.forEachIndexed { index, component ->
            val label = component.id.ifBlank { "components[$index]" }
            if (component.id.isBlank()) errors += "$label: id must not be blank"
            if (component.type !in ManifestContentTypes.ALL) errors += "$label: unsupported type ${component.type}"
            if (component.name.isBlank()) errors += "$label: name must not be blank"
            if (component.version.isBlank()) errors += "$label: version must not be blank"
            if (component.channel !in CHANNELS) errors += "$label: unsupported channel ${component.channel}"
            if (component.abi.isEmpty() || component.abi.any(String::isBlank)) errors += "$label: abi must not be empty"
            if (component.archiveFormat.isBlank()) errors += "$label: archiveFormat must not be blank"
            if (component.urls.isEmpty()) errors += "$label: urls must not be empty"
            component.urls.forEach { url ->
                if (!url.startsWith("https://", ignoreCase = true)) errors += "$label: URL must use HTTPS: $url"
            }
            if (component.sizeBytes <= 0) errors += "$label: sizeBytes must be positive"
            if (!SHA256.matches(component.sha256)) errors += "$label: sha256 must contain 64 hexadecimal characters"
            if (!component.sourceRepository.startsWith("https://", ignoreCase = true)) {
                errors += "$label: sourceRepository must use HTTPS"
            }
            if (!SOURCE_COMMIT.matches(component.sourceCommit)) errors += "$label: sourceCommit must be a commit hash"
            if (component.license.isBlank()) errors += "$label: license must not be blank"
            if (component.pageSizes.isEmpty() || component.pageSizes.any { it !in PAGE_SIZES }) {
                errors += "$label: pageSizes must contain supported Android page sizes"
            }
            if (component.requiredFiles.isEmpty()) errors += "$label: requiredFiles must not be empty"
            component.requiredFiles.forEach { path ->
                if (!isSafeRelativePath(path)) errors += "$label: requiredFiles contains unsafe path: $path"
            }
            component.requires.forEach { dependency ->
                if (dependency !in knownIds) errors += "$label: missing dependency: $dependency"
                if (component.channel == "stable" && channelById[dependency] == "experimental") {
                    errors += "$label: stable component depends on experimental component: $dependency"
                }
            }
        }
        return errors
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
        return path.replace('\\', '/').split('/').none { it == ".." || it.isBlank() }
    }
}
