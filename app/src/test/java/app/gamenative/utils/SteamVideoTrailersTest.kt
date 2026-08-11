package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamVideoTrailersTest {

    @Test
    fun `max quality mp4 wins over 480`() {
        val json = """
            {"220":{"success":true,"data":{"movies":[
                {"id":1,"mp4":{"480":"https://cdn.example/t480.mp4","max":"https://cdn.example/tmax.mp4"}}
            ]}}}
        """.trimIndent()

        assertEquals("https://cdn.example/tmax.mp4", SteamVideoTrailers.parse(json, 220))
    }

    @Test
    fun `falls back to 480 and null on junk`() {
        val only480 = """
            {"220":{"success":true,"data":{"movies":[{"id":1,"mp4":{"480":"https://cdn.example/t480.mp4"}}]}}}
        """.trimIndent()

        assertEquals("https://cdn.example/t480.mp4", SteamVideoTrailers.parse(only480, 220))
        assertNull(SteamVideoTrailers.parse("junk", 220))
        assertNull(SteamVideoTrailers.parse("""{"220":{"success":false}}""", 220))
    }
}
