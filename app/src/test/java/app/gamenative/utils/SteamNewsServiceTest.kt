package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamNewsServiceTest {

    @Test
    fun `parses news items with title url and date`() {
        val json = """
            {"appnews":{"appid":220,"newsitems":[
                {"gid":"1","title":"Update 1","url":"https://store.steampowered.com/news/1","date":1700000000},
                {"gid":"2","title":"","url":"https://store.steampowered.com/news/2","date":1700000100},
                {"gid":"3","title":"Update 3","url":"https://store.steampowered.com/news/3","date":1700000200}
            ]}}
        """.trimIndent()

        val items = SteamNewsService.parse(json)

        assertEquals(2, items.size)
        assertEquals("Update 1", items[0].title)
        assertEquals(1700000000L, items[0].dateEpochSec)
    }

    @Test
    fun `invalid payloads return empty list`() {
        assertTrue(SteamNewsService.parse("").isEmpty())
        assertTrue(SteamNewsService.parse("junk").isEmpty())
        assertTrue(SteamNewsService.parse("{}").isEmpty())
    }
}
