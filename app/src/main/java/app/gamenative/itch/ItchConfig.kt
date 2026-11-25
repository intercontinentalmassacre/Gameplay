package app.gamenative.itch

import app.gamenative.BuildConfig

/**
 * Single configuration point for the itch.io integration.
 *
 * To enable live itch.io login, register an OAuth application at
 * <https://itch.io/user/settings/oauth-apps> with the exact redirect URI
 * below and provide its client id as `ITCH_CLIENT_ID` (Gradle property,
 * environment variable or `local.properties` entry). itch.io's authorization
 * code flow uses PKCE and has no client secret, so a client id is the only
 * value that has to be supplied.
 *
 * Tests point [apiBaseUrl]/[authorizeBaseUrl] at a local HTTP server instead of
 * overriding anything at runtime; there is no hidden second configuration path.
 */
object ItchConfig {
    /** Gradle-property/env name that carries the OAuth application client id. */
    const val CLIENT_ID_PROPERTY = "ITCH_CLIENT_ID"

    val clientId: String = BuildConfig.ITCH_CLIENT_ID

    /** Registered redirect URI. Must match the OAuth application settings exactly. */
    const val REDIRECT_URI = "gameplay://itch-oauth/callback"

    const val REDIRECT_SCHEME = "gameplay"
    const val REDIRECT_HOST = "itch-oauth"
    const val REDIRECT_PATH = "/callback"

    /** Public API host. Only API calls (never CDN downloads) receive the Bearer token. */
    val apiBaseUrl: String = "https://api.itch.io"

    /** Authorization endpoint host. */
    val authorizeBaseUrl: String = "https://itch.io"

    const val USER_AGENT = "Gameplay-Android (itch.io integration)"

    /**
     * Minimal scopes: owned library + uploads/downloads. `profile:me` is included
     * because the UI shows the connected account name and avatar.
     */
    val scopes: List<String> = listOf(
        "profile:owned",
        "game:view:uploads",
        "profile:me",
    )

    val scopeString: String = scopes.joinToString(" ")

    fun isConfigured(): Boolean = clientId.isNotBlank()

    fun authorizeUrl(): String = "$authorizeBaseUrl/user/oauth"

    fun tokenUrl(): String = "$apiBaseUrl/oauth/token"

    fun revokeUrl(): String = "$apiBaseUrl/oauth/revoke"
}
