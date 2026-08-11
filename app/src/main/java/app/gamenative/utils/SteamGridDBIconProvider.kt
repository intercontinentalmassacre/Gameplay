package app.gamenative.utils

import app.gamenative.PrefManager
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
 * Native square game icons from SteamGridDB (`/icons/steam/<appid>`), used by
 * the Switch-style compact row and the DS_HOME grid instead of cropping
 * capsule art to a square. URL resolution is cached in memory; Coil caches
 * the actual images.
 */
object SteamGridDBIconProvider {

    private const val API_BASE_URL = "https://www.steamgriddb.com/api/v2"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<Int, String?>()
    private val cacheMutex = Mutex()

    /** Returns the icon URL for a Steam app, or null when unavailable/disabled. */
    suspend fun iconForSteamApp(appId: Int): String? {
        cacheMutex.withLock {
            if (cache.containsKey(appId)) return cache[appId]
        }

        val resolved = fetchIconUrl(appId)
        cacheMutex.withLock {
            cache[appId] = resolved
        }
        return resolved
    }

    private suspend fun fetchIconUrl(appId: Int): String? = withContext(Dispatchers.IO) {
        val apiKey = app.gamenative.BuildConfig.STEAMGRIDDB_API_KEY
        if (apiKey.isBlank() || !PrefManager.fetchSteamGridDBImages) return@withContext null

        runCatching {
            val request = Request.Builder()
                .url("$API_BASE_URL/icons/steam/$appId")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                parse(body)
            }
        }.onFailure { Timber.e(it, "SGDB icon fetch failed for $appId") }
            .getOrNull()
    }

    internal fun parse(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.optBoolean("success", false)) return null
        val data = root.optJSONArray("data") ?: return null

        var firstStatic: String? = null
        for (index in 0 until data.length()) {
            val entry = data.optJSONObject(index) ?: continue
            val url = entry.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (entry.optString("type") == "static") {
                if (firstStatic == null) firstStatic = url
            }
        }
        return firstStatic
    }
}
