package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** Current player count from the public GetNumberOfCurrentPlayers endpoint. */
object SteamPlayerCount {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(appId: Int): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(
                    "https://api.steampowered.com/ISteamUserStats/" +
                        "GetNumberOfCurrentPlayers/v0001/?appid=$appId&format=json",
                )
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                runCatching {
                    JSONObject(body)
                        .getJSONObject("response")
                        .getInt("player_count")
                }.getOrNull()
            }
        }.onFailure { Timber.e(it, "Failed to fetch player count for $appId") }
            .getOrNull()
    }

    /** 352 -> "352", 1_250 -> "1.3K", 25_000 -> "25K" */
    fun formatCount(count: Int): String = when {
        count >= 1_000 -> "%.1fK".format(count / 1000f).replace(".0K", "K")
        else -> count.toString()
    }
}
