package app.gamenative.itch

import android.util.Xml
import app.gamenative.data.ItchGame
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

internal data class CatalogGame(val title: String, val url: String, val image: String)

internal enum class CatalogOrder(val path: String, val label: Int) {
    POPULAR("", app.gamenative.R.string.itch_sort_popular),
    NEWEST("newest/", app.gamenative.R.string.community_config_newest),
    RATED("top-rated/", app.gamenative.R.string.itch_sort_rated),
}

internal object ItchFreeCatalog {
    suspend fun games(category: String, page: Int, order: CatalogOrder): List<CatalogGame> = withContext(Dispatchers.IO) {
        require(page > 0)
        val path = if (category.isEmpty()) "free" else "free/$category"
        val request = Request.Builder().url("https://itch.io/games/${order.path}$path.xml?page=$page").build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("itch.io HTTP ${response.code}")
            val parser = Xml.newPullParser()
            parser.setInput(response.body!!.byteStream(), null)
            val games = mutableListOf<CatalogGame>()
            var fields: MutableMap<String, String>? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (tag == "item") fields = mutableMapOf()
                    else if (fields != null && tag in setOf("plainTitle", "link", "imageurl", "price")) {
                        fields[tag] = parser.nextText()
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && tag == "item") {
                    fields?.let {
                        val url = it["link"]?.toHttpUrlOrNull()
                        if (url != null && url.isHttps && url.host.endsWith(".itch.io") &&
                            !it["plainTitle"].isNullOrBlank() && it["price"] == "$0.00") {
                            games.add(CatalogGame(it.getValue("plainTitle"), url.toString(), it["imageurl"].orEmpty()))
                        }
                    }
                    fields = null
                }
            }
            games
        }
    }

    suspend fun details(game: CatalogGame): ItchGame = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(game.url.trimEnd('/') + "/data.json").build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("itch.io HTTP ${response.code}")
            val data = JSONObject(response.body!!.string())
            val author = data.optJSONArray("authors")?.optJSONObject(0)
            ItchGame(gameId = data.getLong("id"), title = data.optString("title", game.title),
                url = game.url, coverUrl = data.optString("cover_image", game.image),
                author = author?.optString("name").orEmpty(), authorUrl = author?.optString("url").orEmpty(),
                isOwned = false, addedAtMs = System.currentTimeMillis())
        }
    }
}
