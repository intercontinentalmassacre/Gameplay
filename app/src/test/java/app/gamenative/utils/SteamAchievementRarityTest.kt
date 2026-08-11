package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamAchievementRarityTest {

    @Test
    fun `parses percentages by schema name`() {
        val json = """
            {"achievementpercentages":{"achievements":[
                {"name":"ACH_WIN_GAME","percent":85.3},
                {"name":"ACH_RARE_ONE","percent":3.2}
            ]}}
        """.trimIndent()

        val result = SteamAchievementRarity.parse(json)

        assertEquals(85.3f, result?.get("ACH_WIN_GAME"))
        assertEquals(3.2f, result?.get("ACH_RARE_ONE"))
    }

    @Test
    fun `invalid or empty payloads return null`() {
        assertNull(SteamAchievementRarity.parse(""))
        assertNull(SteamAchievementRarity.parse("not json"))
        assertNull(SteamAchievementRarity.parse("{}"))
    }
}
