package app.gamenative.itch

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth 2.0 authorization-code flow with PKCE (RFC 7636) for itch.io.
 *
 * itch.io's OAuth application flow has no client secret: the PKCE verifier is
 * the only proof that the app that started the login is the one finishing it.
 * The verifier never leaves the device; only its S256 challenge is sent to the
 * authorization endpoint.
 */
object ItchOAuth {
    private const val VERIFIER_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** Generates a 64-character PKCE code verifier (RFC 7636 allows 43..128). */
    fun generateVerifier(random: SecureRandom = SecureRandom()): String {
        val builder = StringBuilder(64)
        repeat(64) {
            builder.append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)])
        }
        return builder.toString()
    }

    /** base64url(SHA-256(verifier)) without padding. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        scopes: List<String> = ItchConfig.scopes,
        authorizeBaseUrl: String = ItchConfig.authorizeBaseUrl,
    ): String {
        require(clientId.isNotBlank()) { "itch.io client id is not configured" }
        require(state.isNotBlank()) { "OAuth state must not be blank" }
        val params = linkedMapOf(
            "client_id" to clientId,
            "scope" to scopes.joinToString(" "),
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
        )
        return "$authorizeBaseUrl/user/oauth?" +
            params.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /** True when [url] targets the registered redirect URI (scheme/host/path). */
    fun isRedirectUri(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals(ItchConfig.REDIRECT_SCHEME, ignoreCase = true)) return false
        if (!uri.host.orEmpty().equals(ItchConfig.REDIRECT_HOST, ignoreCase = true)) return false
        val path = uri.path.orEmpty().ifBlank { "/" }
        return path == ItchConfig.REDIRECT_PATH
    }

    data class Redirect(
        val code: String? = null,
        val state: String? = null,
        val error: String? = null,
    ) {
        val isAuthorizationCode: Boolean get() = !code.isNullOrBlank() && error.isNullOrBlank()
    }

    /** Parses `gameplay://itch-oauth/callback?code=…&state=…` (returns null for foreign URLs). */
    fun parseRedirect(url: String): Redirect? {
        if (!isRedirectUri(url)) return null
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val params = parseQuery(query)
        return Redirect(
            code = params["code"]?.takeIf(String::isNotBlank),
            state = params["state"]?.takeIf(String::isNotBlank),
            error = params["error"]?.takeIf(String::isNotBlank),
        )
    }

    /**
     * Constant-time state comparison. A missing or mismatched state must abort the
     * exchange: the redirect could belong to another login attempt or be forged.
     */
    fun stateMatches(expected: String, actual: String?): Boolean {
        if (expected.isBlank() || actual.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val name = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(name) to decode(value)
        }.toMap()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
