package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Store review summary from the stable (unofficial) appreviews endpoint. */
object SteamReviewScore {

    enum class Sentiment { POSITIVE, MIXED, NEGATIVE }

    data class Score(
        val description: String,
        val percentPositive: Int,
        val totalReviews: Int,
        val sentiment: Sentiment,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(appId: Int): Score? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(
                    "https://store.steampowered.com/appreviews/$appId" +
                        "?json=1&language=all&purchase_type=all",
                )
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty())
            }
        }.onFailure { Timber.e(it, "Failed to fetch review score for $appId") }
            .getOrNull()
    }

    internal fun parse(json: String): Score? {
        val summary = runCatching {
            JSONObject(json).getJSONObject("query_review_summary")
        }.getOrNull() ?: return null

        val total = summary.optInt("total_reviews", 0)
        if (total <= 0) return null
        val positive = summary.optInt("total_positive", 0)
        val percent = (positive * 100) / total
        val description = summary.optString("review_score_desc").ifBlank { return null }

        val sentiment = when {
            description.contains("Negative", ignoreCase = true) -> Sentiment.NEGATIVE
            description.contains("Positive", ignoreCase = true) -> Sentiment.POSITIVE
            else -> Sentiment.MIXED
        }
        return Score(
            description = description,
            percentPositive = percent,
            totalReviews = total,
            sentiment = sentiment,
        )
    }
}
