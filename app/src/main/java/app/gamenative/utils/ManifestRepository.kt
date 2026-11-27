package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import timber.log.Timber

object ManifestRepository {
    private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/intercontinentalmassacre/Gameplay/refs/heads/main/manifest.json"
    private val json = Json { ignoreUnknownKeys = true }
    private var bundledManifest: ManifestData? = null
    private var lastManifestText: String? = null
    private var lastParsedManifest: ManifestData? = null

    suspend fun loadManifest(context: Context, preferRemote: Boolean = false): ManifestData {
        val bundled = readLocalManifest(context)
        if (BuildConfig.DEBUG && !preferRemote) {
            bundled?.let {
                Timber.i("ManifestRepository: using local debug manifest")
                return it
            }
        }

        val cachedJson = PrefManager.componentManifestJson
        val cachedManifest = parseManifest(cachedJson)
        val lastFetchedAt = PrefManager.componentManifestFetchedAt
        val isStale = System.currentTimeMillis() - lastFetchedAt >= ONE_DAY_MS

        if (cachedManifest != null && !isStale && !preferRemote) {
            return selectManifest(bundled, cachedManifest)
        }

        val fetched = fetchManifestJson()
        if (fetched != null) {
            val parsed = parseManifest(fetched)
            if (parsed != null) {
                val now = System.currentTimeMillis()
                PrefManager.componentManifestJson = fetched
                PrefManager.componentManifestFetchedAt = now
                return selectManifest(bundled, parsed)
            }
        }

        return selectManifest(bundled, cachedManifest)
    }

    internal fun loadCachedOrBundledManifest(context: Context): ManifestData =
        selectManifest(readLocalManifest(context), parseManifest(PrefManager.componentManifestJson))

    // An APK can ship a newer dated v1 catalog before the public branch is updated.
    internal fun selectManifest(bundled: ManifestData?, remote: ManifestData?): ManifestData = when {
        remote == null -> bundled ?: ManifestData.empty()
        bundled == null || remote.schemaVersion > bundled.schemaVersion -> remote
        bundled.updatedAt.orEmpty() > remote.updatedAt.orEmpty() -> bundled
        else -> remote
    }

    private suspend fun fetchManifestJson(): String? = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(MANIFEST_URL).build()
        Net.http.newCall(request).execute().use { response ->
            response.takeIf { it.isSuccessful }?.body?.string()
        }
    } catch (e: Exception) {
        Timber.e(e, "ManifestRepository: fetch failed")
        null
    }
}

    @Synchronized
    internal fun readLocalManifest(context: Context): ManifestData? {
        bundledManifest?.let { return it }
        return try {
            context.assets.open("manifest.json").bufferedReader().use { parseManifest(it.readText()) }
                .also { bundledManifest = it }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun parseManifest(jsonString: String?): ManifestData? {
        if (jsonString.isNullOrBlank()) return null
        if (jsonString == lastManifestText) return lastParsedManifest
        val parsed = try {
            val normalized = jsonString.removePrefix("\uFEFF").trimStart()
            val root = json.parseToJsonElement(normalized).jsonObject
            when (val schemaVersion = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1) {
                1 -> json.decodeFromString<ManifestData>(normalized)
                2 -> {
                    val catalog = json.decodeFromString<ComponentCatalogDocument>(normalized)
                    val errors = ComponentCatalogValidator.validate(catalog)
                    require(errors.isEmpty()) { errors.joinToString(separator = "; ") }
                    catalog.toManifestData()
                }
                else -> error("Unsupported component catalog schemaVersion=$schemaVersion")
            }
        } catch (e: Exception) {
            Timber.e(e, "ManifestRepository: parse failed")
            null
        }
        lastManifestText = jsonString
        lastParsedManifest = parsed
        return parsed
    }
}
