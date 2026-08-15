package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Lightweight, store-facing metadata for the game card. PICS is the source of
 * truth for the owned library; this endpoint only enriches the presentation
 * with the information players expect on a console game page.
 */
object SteamStoreDetails {
    data class Details(
        val shortDescription: String,
        val genres: List<String>,
        val categories: List<String>,
        val screenshots: List<String>,
        val metacriticScore: Int?,
        val controllerSupport: String?,
        val achievementCount: Int?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(appId: Int): Details? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(
                    "https://store.steampowered.com/api/appdetails?appids=$appId" +
                        "&filters=short_description,genres,categories,screenshots,metacritic,controller_support,achievements",
                )
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty(), appId)
            }
        }.onFailure { Timber.w(it, "Failed to fetch Steam store details for $appId") }
            .getOrNull()
    }

    internal fun parse(json: String, appId: Int): Details? {
        val data = runCatching {
            JSONObject(json).getJSONObject(appId.toString()).getJSONObject("data")
        }.getOrNull() ?: return null

        fun namedArray(key: String): List<String> {
            val entries = data.optJSONArray(key) ?: return emptyList()
            return List(entries.length()) { index ->
                entries.optJSONObject(index)?.optString("description").orEmpty()
            }.filter { it.isNotBlank() }
        }

        val screenshots = data.optJSONArray("screenshots")?.let { entries ->
            List(entries.length()) { index ->
                entries.optJSONObject(index)?.optString("path_full").orEmpty()
            }.filter { it.isNotBlank() }
        }.orEmpty()
        val score = data.optJSONObject("metacritic")?.optInt("score", 0)?.takeIf { it > 0 }
        val achievementCount = data.optJSONObject("achievements")
            ?.optInt("total", 0)
            ?.takeIf { it > 0 }

        return Details(
            shortDescription = data.optString("short_description").trim(),
            genres = namedArray("genres"),
            categories = namedArray("categories"),
            screenshots = screenshots,
            metacriticScore = score,
            controllerSupport = data.optString("controller_support").takeIf { it.isNotBlank() },
            achievementCount = achievementCount,
        )
    }
}
