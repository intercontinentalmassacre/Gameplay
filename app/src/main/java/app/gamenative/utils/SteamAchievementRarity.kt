package app.gamenative.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Global unlock percentages per achievement (rarity) from the public Steam
 * Web API endpoint GetGlobalAchievementPercentagesForApp. No API key needed.
 * Cached in memory for 24h per app.
 */
object SteamAchievementRarity {

    private const val TTL_MS = 24L * 60 * 60 * 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<Int, Pair<Long, Map<String, Float>>>()
    private val cacheMutex = Mutex()

    /** Returns schema-name -> unlock percentage (0..100), or null on failure. */
    suspend fun fetch(appId: Int): Map<String, Float>? {
        cacheMutex.withLock {
            cache[appId]?.let { (timestamp, data) ->
                if (System.currentTimeMillis() - timestamp < TTL_MS) return data
            }
        }

        val fetched = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(
                        "https://api.steampowered.com/ISteamUserStats/" +
                            "GetGlobalAchievementPercentagesForApp/v0002/" +
                            "?gameid=$appId&format=json",
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    parse(response.body?.string().orEmpty())
                }
            }.onFailure { Timber.e(it, "Failed to fetch achievement rarity for $appId") }
                .getOrNull()
        } ?: return null

        cacheMutex.withLock {
            cache[appId] = System.currentTimeMillis() to fetched
        }
        return fetched
    }

    internal fun parse(json: String): Map<String, Float>? {
        if (json.isBlank()) return null
        val achievements = runCatching {
            JSONObject(json)
                .getJSONObject("achievementpercentages")
                .getJSONArray("achievements")
        }.getOrNull() ?: return null

        val result = mutableMapOf<String, Float>()
        for (index in 0 until achievements.length()) {
            val entry = achievements.optJSONObject(index) ?: continue
            val name = entry.optString("name")
            if (name.isNotBlank()) {
                result[name] = entry.optDouble("percent", Double.NaN)
                    .takeUnless { it.isNaN() }?.toFloat() ?: continue
            }
        }
        return result
    }
}
