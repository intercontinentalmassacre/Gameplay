package com.winlator.xenvironment.components

import com.winlator.core.envvars.EnvVars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvRedactorTest {

    @Test
    fun sensitiveKeysAreMasked() {
        val env = EnvVars()
        env.put("HOME", "/home/user")
        val secrets = linkedMapOf(
            "SteamUser" to "private-user",
            "STEAMID" to "private-steam-id",
            "MY_TOKEN" to "private-token",
            "LOGIN_PASSWORD" to "private-password",
            "CLIENT_SECRET" to "private-secret",
            "AUTH_CODE" to "private-auth",
            "WEB_COOKIE" to "private-cookie",
            "LOGIN_SESSION" to "private-session",
            "API_KEY" to "private-key",
            "REFRESH_VALUE" to "private-refresh",
        )
        secrets.forEach { (key, value) -> env.put(key, value) }

        val out = EnvRedactor.redact(env)

        assertTrue(out.contains("HOME=/home/user"))
        secrets.forEach { (key, value) ->
            assertTrue(out.contains("$key=<redacted>"))
            assertFalse(out.contains(value))
        }
    }

    @Test
    fun rawExecEnvironmentIsMasked() {
        val out = EnvRedactor.redact(
            arrayOf(
                "HOME=/home/user",
                "AUTH_TOKEN=raw-token",
                "SteamUser=private-name",
                "AMAZON_GAMES_FUEL_ENTITLEMENT_ID=private-entitlement",
            ),
        )

        assertTrue(out.contains("HOME=/home/user"))
        assertTrue(out.contains("AUTH_TOKEN=<redacted>"))
        assertTrue(out.contains("SteamUser=<redacted>"))
        assertTrue(out.contains("AMAZON_GAMES_FUEL_ENTITLEMENT_ID=<redacted>"))
        assertFalse(out.contains("raw-token"))
        assertFalse(out.contains("private-name"))
        assertFalse(out.contains("private-entitlement"))
    }

    @Test
    fun commandCredentialsAreMasked() {
        val command =
            "winhandler.exe -AUTH_PASSWORD=secret-password " +
                "-epicovt=ownership-token " +
                "https://example.test/callback?openid.oa2.authorization_code=oauth-code " +
                "Bearer bearer-token"

        val out = EnvRedactor.redactText(command)

        assertTrue(out.contains("-AUTH_PASSWORD=<redacted>"))
        assertTrue(out.contains("-epicovt=<redacted>"))
        assertTrue(out.contains("authorization_code=<redacted>"))
        assertTrue(out.contains("Bearer <redacted>"))
        assertFalse(out.contains("secret-password"))
        assertFalse(out.contains("ownership-token"))
        assertFalse(out.contains("oauth-code"))
        assertFalse(out.contains("bearer-token"))
    }

    @Test
    fun shellQuoteHandlesSpecialCases() {
        assertEquals("plain", EnvRedactor.shellQuote("plain"))
        assertEquals("/path/with-dash/file_1.exe", EnvRedactor.shellQuote("/path/with-dash/file_1.exe"))
        assertEquals("'C:\\Games\\My Game\\game.exe'", EnvRedactor.shellQuote("C:\\Games\\My Game\\game.exe"))
        assertEquals("'Игра Судьбы.exe'", EnvRedactor.shellQuote("Игра Судьбы.exe"))
        assertEquals("'a&b;\$HOME`x`'", EnvRedactor.shellQuote("a&b;\$HOME`x`"))
        assertEquals("'it'\\''s.exe'", EnvRedactor.shellQuote("it's.exe"))
    }
}
