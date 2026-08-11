package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Trailer video URL from the store appdetails endpoint (movies filter). */
object SteamVideoTrailers {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Best available mp4 (max quality) trailer URL, or null. */
    suspend fun fetchTrailerUrl(appId: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://store.steampowered.com/api/appdetails?appids=$appId&filters=movies")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parse(response.body?.string().orEmpty(), appId)
            }
        }.onFailure { Timber.e(it, "Failed to fetch trailer for $appId") }
            .getOrNull()
    }

    internal fun parse(json: String, appId: Int): String? {
        val movies = runCatching {
            JSONObject(json)
                .getJSONObject(appId.toString())
                .getJSONObject("data")
                .getJSONArray("movies")
        }.getOrNull() ?: return null

        for (index in 0 until movies.length()) {
            val mp4 = movies.optJSONObject(index)
                ?.optJSONObject("mp4") ?: continue
            val max = mp4.optString("max")
            if (max.isNotBlank()) return max
            val p480 = mp4.optString("480")
            if (p480.isNotBlank()) return p480
        }
        return null
    }
}
