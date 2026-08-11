package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamGridDBIconProviderTest {

    @Test
    fun `first static icon wins`() {
        val json = """
            {"success":true,"data":[
                {"id":1,"type":"animated","url":"https://cdn.example/animated.webp"},
                {"id":2,"type":"static","url":"https://cdn.example/icon.png"}
            ]}
        """.trimIndent()

        assertEquals("https://cdn.example/icon.png", SteamGridDBIconProvider.parse(json))
    }

    @Test
    fun `no static icons or failed payloads return null`() {
        assertNull(SteamGridDBIconProvider.parse("""{"success":true,"data":[{"type":"animated","url":"u"}]}"""))
        assertNull(SteamGridDBIconProvider.parse("""{"success":false,"data":[]}"""))
        assertNull(SteamGridDBIconProvider.parse("junk"))
    }
}
