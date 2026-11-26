package app.gamenative.itch

/** What an upload actually is, from the player's point of view. */
enum class ItchUploadRole {
    GAME,
    DEMO,
    EDITOR,
    MANUAL,
    SOUNDTRACK,
    ART,
    OTHER,
}

/** A Windows-installable upload, enriched with the fields the picker shows. */
data class ItchUploadCandidate(
    val id: Long,
    val filename: String,
    val displayName: String,
    val sizeBytes: Long,
    val channelName: String,
    val role: ItchUploadRole,
    val versionLabel: String?,
    val buildId: Long?,
    val updatedAt: String?,
    val sizeLabel: String,
) {
    val title: String get() = displayName.ifBlank { filename }
}

/**
 * Result of picking an upload. [Single] is only produced for exactly one
 * Windows *game* file: extras like soundtracks, editors or manuals must never
 * make the download button ambiguous, and a lone demo is never auto-installed.
 */
sealed interface ItchUploadChoice {
    data class Single(val upload: ItchUploadCandidate) : ItchUploadChoice

    data class Multiple(
        val uploads: List<ItchUploadCandidate>,
        val gameCandidates: Int,
    ) : ItchUploadChoice

    data class None(val reason: Reason) : ItchUploadChoice

    enum class Reason {
        NO_UPLOADS,
        NO_WINDOWS_BUILD,
    }
}

/**
 * Decides which upload to download and how to describe it.
 *
 * itch.io exposes platform flags, a file type, a channel name and the original
 * filename. Hand-uploaded files have no build metadata, so classification falls
 * back to the type/filename heuristics itch.io's own app uses.
 */
object ItchUploadSelector {
    private val installableExtensions = listOf(".zip", ".exe", ".msi")
    private val nonGameTypes = setOf("soundtrack", "book", "art", "other", "html")

    fun isCartridge(filename: String) =
        filename.endsWith(".gb", true) || filename.endsWith(".gbc", true)

    fun isCartridgeArchive(filename: String) =
        filename.endsWith(".gb.zip", true) || filename.endsWith(".gbc.zip", true)

    fun isHtmlArchive(upload: ItchUpload): Boolean =
        upload.type.equals("html", true) && upload.filename.endsWith(".zip", true)

    fun isWindowsBuild(upload: ItchUpload): Boolean {
        val platforms = upload.platforms
        if (platforms.isNotEmpty()) {
            return platforms["windows"] == true
        }
        if ("p_windows" in upload.traits) return true
        if (upload.traits.any { it == "p_linux" || it == "p_osx" || it == "p_android" }) return false
        val channel = upload.channelName.lowercase()
        if (channel.startsWith("windows") || channel in setOf("win", "win32", "win64", "windows32", "windows64")) {
            return true
        }
        val explicitlyOtherPlatform = listOf("linux", "osx", "mac", "android", "web").any {
            channel.startsWith(it) || channel.contains("-$it")
        }
        if (explicitlyOtherPlatform) return false
        return upload.filename.lowercase().endsWith(".exe") ||
            upload.filename.lowercase().endsWith(".msi")
    }

    fun isInstallableFile(upload: ItchUpload): Boolean {
        val name = upload.filename.lowercase()
        if (name.isBlank()) return false
        if (isHtmlArchive(upload) || isCartridge(name)) return true
        if (nonGameTypes.contains(upload.type.lowercase())) return false
        return installableExtensions.any(name::endsWith)
    }

    fun roleOf(upload: ItchUpload): ItchUploadRole {
        when (upload.type.lowercase()) {
            "soundtrack" -> return ItchUploadRole.SOUNDTRACK
            "book" -> return ItchUploadRole.MANUAL
            "art" -> return ItchUploadRole.ART
            "html" -> if (!isHtmlArchive(upload)) return ItchUploadRole.OTHER
        }
        val haystack = "${upload.filename} ${upload.displayName} ${upload.channelName}".lowercase()
        return when {
            containsAny(haystack, DEMO_MARKERS) -> ItchUploadRole.DEMO
            containsAny(haystack, EDITOR_MARKERS) -> ItchUploadRole.EDITOR
            containsAny(haystack, MANUAL_MARKERS) -> ItchUploadRole.MANUAL
            containsAny(haystack, SOUNDTRACK_MARKERS) -> ItchUploadRole.SOUNDTRACK
            containsAny(haystack, ART_MARKERS) -> ItchUploadRole.ART
            else -> ItchUploadRole.GAME
        }
    }

    fun candidates(uploads: List<ItchUpload>): List<ItchUploadCandidate> = uploads
        .asSequence()
        .filter { isWindowsBuild(it) || isHtmlArchive(it) || isCartridge(it.filename) || isCartridgeArchive(it.filename) }
        .filter(::isInstallableFile)
        .map(::toCandidate)
        .sortedWith(compareBy({ roleRank(it.role) }, { it.title.lowercase() }))
        .toList()

    fun choose(uploads: List<ItchUpload>): ItchUploadChoice {
        val candidates = candidates(uploads)
        if (candidates.isEmpty()) {
            return ItchUploadChoice.None(
                if (uploads.isEmpty()) {
                    ItchUploadChoice.Reason.NO_UPLOADS
                } else {
                    ItchUploadChoice.Reason.NO_WINDOWS_BUILD
                },
            )
        }
        val games = candidates.filter { it.role == ItchUploadRole.GAME }
        if (games.size == 1) return ItchUploadChoice.Single(games.single())
        return ItchUploadChoice.Multiple(
            uploads = candidates,
            gameCandidates = games.size,
        )
    }

    fun findCandidate(uploads: List<ItchUpload>, uploadId: Long): ItchUploadCandidate? =
        uploads.firstOrNull { it.id == uploadId }?.let(::toCandidate)

    fun toCandidate(upload: ItchUpload): ItchUploadCandidate = ItchUploadCandidate(
        id = upload.id,
        filename = upload.filename,
        displayName = upload.displayName,
        sizeBytes = upload.size,
        channelName = upload.channelName,
        role = roleOf(upload),
        versionLabel = versionLabel(upload),
        buildId = upload.build?.id,
        updatedAt = upload.updatedAt.takeIf(String::isNotBlank),
        sizeLabel = formatSize(upload.size),
    )

    fun versionLabel(upload: ItchUpload): String? {
        val build = upload.build ?: return null
        return build.userVersion?.takeIf(String::isNotBlank)
            ?: build.version?.toString()
            ?: build.id?.toString()
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit += 1
        }
        return if (unit == 0) "${bytes} B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun roleRank(role: ItchUploadRole): Int = when (role) {
        ItchUploadRole.GAME -> 0
        ItchUploadRole.DEMO -> 1
        ItchUploadRole.EDITOR -> 2
        ItchUploadRole.MANUAL -> 3
        ItchUploadRole.SOUNDTRACK -> 4
        ItchUploadRole.ART -> 5
        ItchUploadRole.OTHER -> 6
    }

    private fun containsAny(haystack: String, markers: List<String>): Boolean =
        markers.any { marker ->
            // Match whole words/tokens so "demolition" or "editorial" don't count.
            Regex("(^|[^a-z0-9])${Regex.escape(marker)}([^a-z0-9]|$)").containsMatchIn(haystack)
        }

    private val DEMO_MARKERS = listOf("demo", "trial", "prologue", "preview")
    private val EDITOR_MARKERS = listOf("editor", "level editor", "mod tools", "toolkit", "sdk")
    private val MANUAL_MARKERS = listOf("manual", "guide", "readme", "instructions", "walkthrough", "book", "novel")
    private val SOUNDTRACK_MARKERS = listOf("soundtrack", "ost", "music", "album", "audio")
    private val ART_MARKERS = listOf("artbook", "art book", "wallpaper", "press kit", "concept art", "sprites")
}
