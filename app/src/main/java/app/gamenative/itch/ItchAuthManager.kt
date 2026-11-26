package app.gamenative.itch

import android.content.Context
import app.gamenative.Crypto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The connected itch.io account as persisted on the device. */
@Serializable
data class ItchAccount(
    val accessToken: String = "",
    val scopes: List<String> = emptyList(),
    val userId: Long = 0,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val siteUrl: String = "",
    val savedAtMs: Long = 0,
) {
    val label: String get() = displayName.ifBlank { username }
}

/** Authorization URL to open in the system browser. */
data class ItchAuthorizationRequest(
    val url: String,
    val state: String,
)

/**
 * Owns the itch.io OAuth session: PKCE generation, state validation, code
 * exchange and encrypted-at-rest token persistence (the same Crypto-backed
 * storage the other store credentials use).
 *
 * The pending verifier/state pair is written to disk (not a process-static) so a
 * login survives the app being killed while the browser is in front, and it is
 * single-use and time limited.
 */
@Singleton
class ItchAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ItchApiClient,
) {
    private val directory: File get() = ItchPaths.root(context)
    private val credentialsFile: File get() = ItchPaths.credentialsFile(context)
    private val pendingFile: File get() = ItchPaths.pendingAuthorizationFile(context)

    @Serializable
    private data class PendingAuthorization(
        val state: String = "",
        val verifier: String = "",
        val clientId: String = "",
        val redirectUri: String = "",
        val createdAtMs: Long = 0,
    )

    fun isConfigured(): Boolean = ItchConfig.isConfigured()

    /** Cheap check used directly from composition. */
    fun hasStoredCredentials(): Boolean = credentialsFile.isFile && credentialsFile.length() > 0

    fun accountOrNull(): ItchAccount? {
        val account = readCredentials() ?: return null
        return account.takeIf { it.accessToken.isNotBlank() }
    }

    fun tokenOrNull(): String? = accountOrNull()?.accessToken

    /**
     * Starts a login: generates a PKCE verifier + state, persists them and
     * returns the authorization URL for the system browser.
     */
    fun beginAuthorization(clientId: String = ItchConfig.clientId): Result<ItchAuthorizationRequest> = runCatching {
        check(clientId.isNotBlank()) {
            "itch.io is not configured: set ${ItchConfig.CLIENT_ID_PROPERTY} to the OAuth application client id"
        }
        val verifier = ItchOAuth.generateVerifier()
        val state = ItchOAuth.generateState()
        val pending = PendingAuthorization(
            state = state,
            verifier = verifier,
            clientId = clientId,
            redirectUri = ItchConfig.REDIRECT_URI,
            createdAtMs = System.currentTimeMillis(),
        )
        writeEncrypted(pendingFile, ItchJson.encodeToString(PendingAuthorization.serializer(), pending))
        val url = api.authorizationUrl(
            clientId = clientId,
            redirectUri = ItchConfig.REDIRECT_URI,
            state = state,
            codeChallenge = ItchOAuth.challenge(verifier),
        )
        ItchAuthorizationRequest(url = url, state = state)
    }

    /**
     * Completes the login from `gameplay://itch-oauth/callback?code=…&state=…`.
     * Foreign redirects, missing/expired sessions, state mismatches and denied
     * authorizations are rejected before any token is stored.
     */
    suspend fun completeAuthorization(redirectUrl: String): Result<ItchAccount> = withContext(Dispatchers.IO) {
        val redirect = ItchOAuth.parseRedirect(redirectUrl)
            ?: return@withContext Result.failure(
                IllegalArgumentException("Not an itch.io authorization redirect"),
            )

        val pending = readPendingAuthorization()
        if (pending == null) {
            return@withContext Result.failure(
                IllegalStateException("No itch.io sign-in is in progress. Start again from the account menu."),
            )
        }
        if (System.currentTimeMillis() - pending.createdAtMs > PENDING_TTL_MS) {
            clearPending()
            return@withContext Result.failure(
                IllegalStateException("The itch.io sign-in expired. Start again from the account menu."),
            )
        }
        if (!ItchOAuth.stateMatches(pending.state, redirect.state)) {
            // A mismatched state means the redirect cannot be trusted. Drop it.
            clearPending()
            return@withContext Result.failure(
                IllegalStateException("itch.io sign-in was rejected: the OAuth state did not match."),
            )
        }
        if (!redirect.error.isNullOrBlank()) {
            clearPending()
            return@withContext Result.failure(
                IllegalStateException("itch.io sign-in was cancelled (${redirect.error})."),
            )
        }
        val code = redirect.code
        if (code.isNullOrBlank()) {
            clearPending()
            return@withContext Result.failure(
                IllegalStateException("itch.io did not return an authorization code."),
            )
        }

        runCatching {
            val token = api.exchangeAuthorizationCode(
                code = code,
                codeVerifier = pending.verifier,
                clientId = pending.clientId.ifBlank { ItchConfig.clientId },
                redirectUri = pending.redirectUri.ifBlank { ItchConfig.REDIRECT_URI },
            )
            val user = runCatching { api.fetchProfile(token.accessToken) }.getOrNull()
            val account = ItchAccount(
                accessToken = token.accessToken,
                scopes = token.scope.split(' ').filter(String::isNotBlank),
                userId = user?.id ?: 0,
                username = user?.username.orEmpty(),
                displayName = user?.displayName.orEmpty(),
                avatarUrl = user?.coverUrl.orEmpty(),
                siteUrl = user?.url.orEmpty(),
                savedAtMs = System.currentTimeMillis(),
            )
            writeEncrypted(credentialsFile, ItchJson.encodeToString(ItchAccount.serializer(), account))
            clearPending()
            account
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )
    }

    /** Revokes the token remotely (best effort) and deletes local credentials. */
    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        val account = readCredentials()
        if (account != null && account.accessToken.isNotBlank()) {
            runCatching { api.revokeToken(account.accessToken, ItchConfig.clientId) }
        }
        credentialsFile.delete()
        clearPending()
        Result.success(Unit)
    }

    fun clearPending() {
        pendingFile.delete()
    }

    private fun readPendingAuthorization(): PendingAuthorization? {
        val raw = readEncrypted(pendingFile) ?: return null
        return runCatching {
            ItchJson.decodeFromString(PendingAuthorization.serializer(), raw)
        }.getOrNull()?.takeIf { it.state.isNotBlank() && it.verifier.isNotBlank() }
    }

    private fun readCredentials(): ItchAccount? {
        val raw = readEncrypted(credentialsFile) ?: return null
        return runCatching {
            ItchJson.decodeFromString(ItchAccount.serializer(), raw)
        }.getOrNull()
    }

    private fun writeEncrypted(file: File, plaintext: String) {
        file.parentFile?.mkdirs()
        val encrypted = Crypto.encryptToString(plaintext.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encrypted)
        if (!tmp.renameTo(file)) {
            file.writeText(encrypted)
            tmp.delete()
        }
    }

    private fun readEncrypted(file: File): String? {
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull()?.takeIf(String::isNotBlank) ?: return null
        // Legacy plaintext payloads are accepted and rewritten encrypted on next save.
        if (raw.trimStart().startsWith("{")) return raw
        return Crypto.decryptFromString(raw)?.let { String(it, Charsets.UTF_8) }
    }

    companion object {
        private const val PENDING_TTL_MS = 10 * 60 * 1000L
    }
}
