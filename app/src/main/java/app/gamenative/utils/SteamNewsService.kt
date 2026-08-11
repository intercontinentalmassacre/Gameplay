package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Latest game news / patch notes from the public GetNewsForApp endpoint. */
object SteamNewsService {

    data class NewsItem(
        val title: String,
        val url: String,
        val dateEpochSec: Long,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(appId: Int, count: Int = 5): List<NewsItem> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(
                    "https://api.steampowered.com/ISteamNews/" +
                        "GetNewsForApp/v0002/?appid=$appId&count=$count&maxlength=0&format=json",
                )
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                parse(response.body?.string().orEmpty())
            }
        }.onFailure { Timber.e(it, "Failed to fetch news for $appId") }
            .getOrNull().orEmpty()
    }

    internal fun parse(json: String): List<NewsItem> {
        val items = runCatching {
            JSONObject(json)
                .getJSONObject("appnews")
                .getJSONArray("newsitems")
        }.getOrNull() ?: return emptyList()

        return buildList {
            for (index in 0 until items.length()) {
                val entry = items.optJSONObject(index) ?: continue
                val title = entry.optString("title")
                val url = entry.optString("url")
                if (title.isBlank() || url.isBlank()) continue
                add(
                    NewsItem(
                        title = title,
                        url = url,
                        dateEpochSec = entry.optLong("date", 0L),
                    ),
                )
            }
        }
    }
}
