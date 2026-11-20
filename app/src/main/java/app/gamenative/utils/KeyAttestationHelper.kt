package app.gamenative.utils

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.ProviderException
import android.util.Base64
import app.gamenative.PrefManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.TimeUnit

/**
 * Handles Android Key Attestation: fetches a server nonce, generates an attested
 * EC key pair in the hardware-backed KeyStore, and extracts the certificate chain
 * for server-side verification.
 */
object KeyAttestationHelper {

    private const val TAG = "KeyAttestationHelper"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchNonce(baseUrl: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/attestation/nonce")
            .get()
            .build()

        return httpClient.newCall(request).readCancellable {
            check(it.isSuccessful) { "Nonce request failed: HTTP ${it.code}" }
            val body = it.body?.string() ?: error("Empty nonce response body")
            JSONObject(body).getString("nonce")
        }
    }

    fun generateAttestedKey(nonce: String): List<String> {
        val alias = "key_attestation_${System.nanoTime()}"
        val challengeBytes = nonce.toByteArray(Charsets.UTF_8)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return try {
            generateKeyPair(alias, challengeBytes, useStrongBox = true)
            keyStore.getCertificateChain(alias).map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
        } finally {
            runCatching { keyStore.deleteEntry(alias) }
                .onFailure { Timber.tag(TAG).w(it, "Could not delete attestation key") }
        }
    }

    private fun generateKeyPair(alias: String, challenge: ByteArray, useStrongBox: Boolean) {
        try {
            val specBuilder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)

            if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                specBuilder.setIsStrongBoxBacked(true)
            }

            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore",
            )
            keyPairGenerator.initialize(specBuilder.build())
            keyPairGenerator.generateKeyPair()
        } catch (e: ProviderException) {
            if (useStrongBox) {
                Timber.tag(TAG).w("StrongBox failed, falling back to TEE: ${e.message}")
                generateKeyPair(alias, challenge, useStrongBox = false)
            } else {
                throw e
            }
        }
    }

    /**
     * Fetches a nonce and generates an attested key, returning both for inclusion
     * in API request bodies. Returns null if attestation is unavailable or fails
     * for any reason, allowing the app to continue without attestation.
     */
    suspend fun getAttestationFields(baseUrl: String): Pair<String, List<String>>? {
        return try {
            val nonce = fetchNonce(baseUrl)
            val chain = runInterruptible(Dispatchers.IO) { generateAttestedKey(nonce) }
            PrefManager.keyAttestationAvailable = true
            Pair(nonce, chain)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Key attestation failed, continuing without it")
            PrefManager.keyAttestationAvailable = false
            null
        }
    }
}
