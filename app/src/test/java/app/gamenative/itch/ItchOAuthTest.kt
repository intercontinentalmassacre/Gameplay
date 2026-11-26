package app.gamenative.itch

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchOAuthTest {
    @Test
    fun `challenge matches the RFC 7636 S256 example`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ItchOAuth.challenge(verifier))
    }

    @Test
    fun `verifier is 64 unreserved characters`() {
        repeat(20) {
            val verifier = ItchOAuth.generateVerifier(SecureRandom())
            assertEquals(64, verifier.length)
            assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        }
    }

    @Test
    fun `authorization url carries every required parameter`() {
        val url = ItchOAuth.authorizationUrl(
            clientId = "client-123",
            redirectUri = ItchConfig.REDIRECT_URI,
            state = "state-abc",
            codeChallenge = "challenge-xyz",
            scopes = listOf("profile:owned", "game:view:uploads"),
            authorizeBaseUrl = "https://itch.io",
        )
        assertTrue(url.startsWith("https://itch.io/user/oauth?"))
        assertTrue(url.contains("client_id=client-123"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=challenge-xyz"))
        assertTrue(url.contains("state=state-abc"))
        assertTrue(url.contains("scope=profile%3Aowned%20game%3Aview%3Auploads"))
        assertTrue(url.contains("redirect_uri=gameplay%3A%2F%2Fitch-oauth%2Fcallback"))
    }

    @Test
    fun `redirect parsing extracts code and state`() {
        val redirect = ItchOAuth.parseRedirect("gameplay://itch-oauth/callback?code=abc123&state=st1")
        assertNotNull(redirect)
        assertEquals("abc123", redirect!!.code)
        assertEquals("st1", redirect.state)
        assertTrue(redirect.isAuthorizationCode)
    }

    @Test
    fun `denied authorization is reported as an error`() {
        val redirect = ItchOAuth.parseRedirect("gameplay://itch-oauth/callback?error=access_denied&state=st1")
        assertNotNull(redirect)
        assertEquals("access_denied", redirect!!.error)
        assertFalse(redirect.isAuthorizationCode)
    }

    @Test
    fun `foreign redirect uris are rejected`() {
        assertNull(ItchOAuth.parseRedirect("https://evil.example/callback?code=abc&state=st"))
        assertNull(ItchOAuth.parseRedirect("gameplay://other/callback?code=abc"))
        assertNull(ItchOAuth.parseRedirect("gameplay://itch-oauth/wrong?code=abc"))
        assertFalse(ItchOAuth.isRedirectUri("http://itch-oauth/callback"))
    }

    @Test
    fun `state comparison rejects missing or different values`() {
        assertTrue(ItchOAuth.stateMatches("abc", "abc"))
        assertFalse(ItchOAuth.stateMatches("abc", "abd"))
        assertFalse(ItchOAuth.stateMatches("abc", null))
        assertFalse(ItchOAuth.stateMatches("", ""))
    }
}
