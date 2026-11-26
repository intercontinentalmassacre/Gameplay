package app.gamenative.itch

import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/** Shared, tolerant JSON configuration for every itch.io API payload. */
internal val ItchJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/** Public profile of the authenticated itch.io user. */
@Serializable
data class ItchUser(
    val id: Long = 0,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val url: String = "",
    val developer: Boolean = false,
) {
    val label: String
        get() = displayName.ifBlank { username }
}

@Serializable
data class ItchGameSummary(
    val id: Long = 0,
    val title: String = "",
    val url: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val classification: String = "",
    val type: String = "",
    @Serializable(with = TraitsSerializer::class) val traits: List<String> = emptyList(),
    val user: ItchUser? = null,
    @SerialName("min_price") val minPrice: Long = 0,
)

/** One owned download key, as returned by `GET /profile/owned-keys`. */
@Serializable
data class ItchOwnedKey(
    val id: Long = 0,
    @SerialName("game_id") val gameId: Long = 0,
    val downloads: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    val game: ItchGameSummary? = null,
)

// itch.io can encode the terminal page's empty list as an empty JSON object.
private open class ItchListSerializer<T>(item: KSerializer<T>) : JsonTransformingSerializer<List<T>>(ListSerializer(item)) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject && element.isEmpty()) JsonArray(emptyList()) else element
}

private object TraitsSerializer : ItchListSerializer<String>(String.serializer())
private object OwnedKeysSerializer : ItchListSerializer<ItchOwnedKey>(ItchOwnedKey.serializer())
private object SearchGamesSerializer : ItchListSerializer<ItchSearchGame>(ItchSearchGame.serializer())

@Serializable
internal data class ItchSearchPage(
    @Serializable(with = SearchGamesSerializer::class) val games: List<ItchSearchGame>,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
internal data class ItchSearchGame(
    val title: String,
    val url: String,
    val classification: String = "",
    @kotlinx.serialization.json.JsonNames("min_price") val minPrice: Long = 0,
    @kotlinx.serialization.json.JsonNames("cover_url") val coverUrl: String = "",
)

private object UploadsSerializer : ItchListSerializer<ItchUpload>(ItchUpload.serializer())

@Serializable
data class ItchOwnedKeysPage(
    val page: Int = 1,
    @SerialName("per_page") val perPage: Int = 0,
    @Serializable(with = OwnedKeysSerializer::class)
    @SerialName("owned_keys") val ownedKeys: List<ItchOwnedKey> = emptyList(),
) {
    val isEmpty: Boolean get() = ownedKeys.isEmpty()
}

/** Butler build metadata attached to an upload (absent for hand-uploaded files). */
@Serializable
data class ItchBuild(
    val id: Long? = null,
    val version: Long? = null,
    @SerialName("user_version") val userVersion: String? = null,
    @SerialName("parent_build_id") val parentBuildId: Long? = null,
    val state: String = "",
)

/**
 * One downloadable file of a project. A single project can expose several:
 * Windows/Linux/macOS builds, demos, soundtracks, editors, manuals…
 */
@Serializable
data class ItchUpload(
    val id: Long = 0,
    val filename: String = "",
    @SerialName("display_name") val displayName: String = "",
    val size: Long = 0,
    val type: String = "",
    @SerialName("channel_name") val channelName: String = "",
    val platforms: Map<String, Boolean> = emptyMap(),
    @Serializable(with = TraitsSerializer::class) val traits: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val build: ItchBuild? = null,
    val preorder: Boolean = false,
    @SerialName("position") val position: Int = 0,
) {
    val title: String get() = displayName.ifBlank { filename }
}

@Serializable
data class ItchUploadsResponse(@Serializable(with = UploadsSerializer::class) val uploads: List<ItchUpload> = emptyList())

/** Response of `GET /uploads/:upload_id/download`: a short-lived CDN URL. */
@Serializable
data class ItchDownloadUrlResponse(
    val url: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
) {
    val resolvedUrl: String get() = url.ifBlank { downloadUrl }
}

@Serializable
data class ItchTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    val scope: String = "",
)

@Serializable
data class ItchProfileResponse(val user: ItchUser = ItchUser())

@Serializable
data class ItchErrorResponse(
    val errors: List<String> = emptyList(),
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
) {
    fun message(): String? = errors.firstOrNull()?.takeIf(String::isNotBlank)
        ?: errorDescription?.takeIf(String::isNotBlank)
        ?: error?.takeIf(String::isNotBlank)
}
