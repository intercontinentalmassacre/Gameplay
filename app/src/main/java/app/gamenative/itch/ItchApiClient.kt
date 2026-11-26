package app.gamenative.itch

import app.gamenative.utils.Net
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Failure modes the UI and download queue have to distinguish. */
class ItchApiException(
    val kind: Kind,
    message: String,
    val statusCode: Int = 0,
    val serverError: String? = null,
) : Exception(message) {
    enum class Kind {
        /** Missing/invalid/revoked token (HTTP 401) — sign in again. */
        UNAUTHORIZED,

        /** Token valid but the scope/ownership check failed (HTTP 403). */
        FORBIDDEN,
        NOT_FOUND,
        RATE_LIMITED,
        SERVER,
        INVALID_RESPONSE,
        NETWORK,
    }
}

/**
 * Client for the itch.io server-side API (api.itch.io), OAuth endpoints and
 * `/profile/owned-keys` pagination.
 *
 * The Bearer token is attached only to requests for [apiBaseUrl]. The temporary
 * download URL returned by `/uploads/:id/download` points at a CDN host, so the
 * downloader resolves it here and fetches it with a separate, unauthenticated
 * request (see ItchDownloadManager). Redirects for that endpoint are not
 * followed automatically so a 302 can never carry the token to a third party.
 */
class ItchApiClient(
    private val http: OkHttpClient = Net.http,
    private val apiBaseUrl: String = ItchConfig.apiBaseUrl,
    private val authorizeBaseUrl: String = ItchConfig.authorizeBaseUrl,
    private val json: Json = ItchJson,
    private val userAgent: String = ItchConfig.USER_AGENT,
) {
    private val apiHttp: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val jsonHttp: OkHttpClient = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        scopes: List<String> = ItchConfig.scopes,
    ): String = ItchOAuth.authorizationUrl(
        clientId = clientId,
        redirectUri = redirectUri,
        state = state,
        codeChallenge = codeChallenge,
        scopes = scopes,
        authorizeBaseUrl = authorizeBaseUrl,
    )

    suspend fun fetchProfile(token: String): ItchUser = withContext(Dispatchers.IO) {
        val body = executeJson(
            Request.Builder()
                .url(url("profile"))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", userAgent)
                .get()
                .build(),
        )
        decode<ItchProfileResponse>(body).user
    }

    /** One page of `GET /profile/owned-keys`. */
    suspend fun fetchOwnedKeys(
        token: String,
        page: Int,
        perPage: Int = DEFAULT_PER_PAGE,
        gameId: Long? = null,
    ): ItchOwnedKeysPage = withContext(Dispatchers.IO) {
        require(page >= 1) { "page starts at 1" }
        require(perPage in 1..MAX_PER_PAGE) { "per_page must be 1..$MAX_PER_PAGE" }
        val request = Request.Builder()
            .url(
                url(
                    "profile/owned-keys",
                    buildMap {
                        put("page", page.toString())
                        put("per_page", perPage.toString())
                        gameId?.let { put("game_ids", it.toString()) }
                    },
                ),
            )
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .get()
            .build()
        decode(executeJson(request))
    }

    /**
     * Walks `/profile/owned-keys` until an empty page is returned. itch.io may
     * return short pages before the last one, so only an empty page terminates.
     */
    suspend fun fetchAllOwnedKeys(
        token: String,
        perPage: Int = DEFAULT_PER_PAGE,
        maxPages: Int = MAX_PAGES,
        onPage: suspend (ItchOwnedKeysPage) -> Unit = {},
    ): List<ItchOwnedKey> = withContext(Dispatchers.IO) {
        val keys = mutableListOf<ItchOwnedKey>()
        var page = 1
        while (page <= maxPages) {
            val response = fetchOwnedKeys(token, page, perPage)
            if (response.isEmpty) break
            onPage(response)
            keys += response.ownedKeys
            page += 1
        }
        keys
    }

    internal suspend fun searchFreeGames(token: String, query: String): List<CatalogGame> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("search/games", mapOf("query" to query)))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .build()
        decode<ItchSearchPage>(executeJson(request)).games.mapNotNull { game ->
            val link = game.url.toHttpUrlOrNull()
            if (game.classification == "game" && game.minPrice == 0L && game.title.isNotBlank() &&
                link != null && link.isHttps && link.host.endsWith(".itch.io")) {
                CatalogGame(game.title, link.toString(), game.coverUrl)
            } else null
        }.distinctBy { it.url }
    }

    suspend fun fetchUploads(token: String, gameId: Long, downloadKeyId: Long? = null): List<ItchUpload> = withContext(Dispatchers.IO) {
        require(gameId > 0) { "game id must be positive" }
        val request = Request.Builder()
            .url(url("games/$gameId/uploads", downloadKeyQuery(downloadKeyId)))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .get()
            .build()
        val body = executeJson(request)
        val parsed = decode<ItchUploadsResponse>(body).uploads
        parsed.sortedBy { it.position }
    }

    /**
     * Resolves a fresh, short-lived download URL for [uploadId].
     * The request is authenticated; the returned URL must be fetched without the
     * Authorization header because it points at a CDN host.
     */
    suspend fun requestDownloadUrl(token: String, uploadId: Long, downloadKeyId: Long? = null): String = withContext(Dispatchers.IO) {
        require(uploadId > 0) { "upload id must be positive" }
        val request = Request.Builder()
            .url(url("uploads/$uploadId/download", downloadKeyQuery(downloadKeyId)))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .get()
            .build()
        apiHttp.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> {
                    val direct = runCatching { decode<ItchDownloadUrlResponse>(body).resolvedUrl }
                        .getOrNull()
                        ?.takeIf(String::isNotBlank)
                    if (direct != null) return@withContext direct
                    // Some deployments redirect straight to the CDN.
                    response.header("Location")?.takeIf(String::isNotBlank)
                        ?: throw ItchApiException(
                            ItchApiException.Kind.INVALID_RESPONSE,
                            "itch.io returned no download URL",
                            response.code,
                        )
                }
                response.code in 300..399 -> {
                    response.header("Location")?.takeIf(String::isNotBlank)
                        ?: throw ItchApiException(
                            ItchApiException.Kind.INVALID_RESPONSE,
                            "itch.io returned an empty redirect",
                            response.code,
                        )
                }
                else -> throw errorFor(response.code, body)
            }
        }
    }

    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        clientId: String,
        redirectUri: String = ItchConfig.REDIRECT_URI,
    ): ItchTokenResponse = withContext(Dispatchers.IO) {
        require(code.isNotBlank()) { "authorization code must not be blank" }
        require(codeVerifier.isNotBlank()) { "PKCE verifier must not be blank" }
        require(clientId.isNotBlank()) { "itch.io client id is not configured" }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(url("oauth/token"))
            .header("User-Agent", userAgent)
            .post(form)
            .build()
        val raw = executeJson(request)
        val token = decode<ItchTokenResponse>(raw)
        if (token.accessToken.isBlank()) {
            throw ItchApiException(
                ItchApiException.Kind.INVALID_RESPONSE,
                "itch.io token response did not contain an access token",
            )
        }
        token
    }

    /** RFC 7009 revocation, best-effort. */
    suspend fun revokeToken(token: String, clientId: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank() || clientId.isBlank()) return@withContext false
        val form = FormBody.Builder()
            .add("token", token)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder()
            .url(url("oauth/revoke"))
            .header("User-Agent", userAgent)
            .post(form)
            .build()
        runCatching {
            jsonHttp.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** True when the token carries the given scope, per `/credentials/info`. */
    suspend fun fetchScopes(token: String): Set<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url("credentials/info"))
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent)
            .get()
            .build()
        runCatching {
            val parsed = decode<CredentialsInfo>(executeJson(request))
            parsed.scopes.toSet()
        }.getOrDefault(emptySet())
    }

    @kotlinx.serialization.Serializable
    private data class CredentialsInfo(val scopes: List<String> = emptyList())

    private fun downloadKeyQuery(id: Long?): Map<String, String> =
        if (id == null) emptyMap() else mapOf("download_key_id" to id.toString())

    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        val builder = (apiBaseUrl.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrlOrNull()
            ?: throw ItchApiException(
                ItchApiException.Kind.INVALID_RESPONSE,
                "Invalid itch.io API base URL: $apiBaseUrl",
            )
        val target = builder.newBuilder()
        query.forEach { (key, value) -> target.addQueryParameter(key, value) }
        return target.build().toString()
    }

    private fun executeJson(request: Request): String {
        val response = try {
            jsonHttp.newCall(request).execute()
        } catch (error: IOException) {
            throw ItchApiException(
                ItchApiException.Kind.NETWORK,
                "Could not reach itch.io: ${error.message ?: error.javaClass.simpleName}",
            )
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw errorFor(it.code, body)
            return body
        }
    }

    private fun errorFor(statusCode: Int, body: String): ItchApiException {
        val serverMessage = runCatching { decode<ItchErrorResponse>(body).message() }.getOrNull()
        val kind = when (statusCode) {
            401 -> ItchApiException.Kind.UNAUTHORIZED
            403 -> ItchApiException.Kind.FORBIDDEN
            404 -> ItchApiException.Kind.NOT_FOUND
            429 -> ItchApiException.Kind.RATE_LIMITED
            in 500..599 -> ItchApiException.Kind.SERVER
            else -> ItchApiException.Kind.INVALID_RESPONSE
        }
        val message = serverMessage ?: when (kind) {
            ItchApiException.Kind.UNAUTHORIZED -> "itch.io sign-in was rejected (HTTP 401)"
            ItchApiException.Kind.FORBIDDEN -> "itch.io denied access to this resource (HTTP 403)"
            ItchApiException.Kind.NOT_FOUND -> "itch.io resource was not found (HTTP 404)"
            ItchApiException.Kind.RATE_LIMITED -> "itch.io rate limit reached (HTTP 429)"
            ItchApiException.Kind.SERVER -> "itch.io is unavailable (HTTP $statusCode)"
            else -> "Unexpected itch.io response (HTTP $statusCode)"
        }
        return ItchApiException(kind, message, statusCode, serverMessage)
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (error: Exception) {
        throw ItchApiException(
            ItchApiException.Kind.INVALID_RESPONSE,
            "Could not read the itch.io response: ${error.message ?: error.javaClass.simpleName}",
        )
    }

    companion object {
        const val DEFAULT_PER_PAGE = 100
        const val MAX_PER_PAGE = 500
        const val MAX_PAGES = 200
    }
}
