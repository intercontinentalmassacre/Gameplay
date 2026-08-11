package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamCommunityStatsTest {

    @Test
    fun `player count formatting`() {
        assertEquals("352", SteamPlayerCount.formatCount(352))
        assertEquals("1.3K", SteamPlayerCount.formatCount(1250))
        assertEquals("25K", SteamPlayerCount.formatCount(25_000))
    }

    @Test
    fun `review score parsing maps buckets and percent`() {
        val json = """
            {"success":1,"query_review_summary":{
                "review_score":8,"review_score_desc":"Very Positive",
                "total_positive":920,"total_negative":80,"total_reviews":1000
            }}
        """.trimIndent()

        val score = SteamReviewScore.parse(json)

        assertEquals("Very Positive", score?.description)
        assertEquals(92, score?.percentPositive)
        assertEquals(SteamReviewScore.Sentiment.POSITIVE, score?.sentiment)
    }

    @Test
    fun `negative and empty reviews`() {
        val negative = SteamReviewScore.parse(
            """{"query_review_summary":{"review_score_desc":"Mostly Negative","total_positive":10,"total_negative":90,"total_reviews":100}}""",
        )
        assertEquals(SteamReviewScore.Sentiment.NEGATIVE, negative?.sentiment)
        assertEquals(10, negative?.percentPositive)

        assertNull(SteamReviewScore.parse("""{"query_review_summary":{"total_reviews":0}}"""))
        assertNull(SteamReviewScore.parse("junk"))
    }
}
