package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URI
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateInfo(
    val updateAvailable: Boolean,
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String? = null,
    val releaseTag: String? = null,
    val packageName: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
)

object UpdateChecker {
    private const val repository = "intercontinentalmassacre/Gameplay"
    private const val latestReleaseUrl = "https://api.github.com/repos/$repository/releases/latest"
    private const val metadataAssetName = "Gameplay-modern-release.json"
    private const val preferencesName = "gameplay_updates"
    private const val cachedInfoKey = "cached_info"
    private const val lastCheckKey = "last_check_at"
    private const val etagKey = "etag"
    private const val checkIntervalMs = 24 * 60 * 60 * 1000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    private data class GitHubAsset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    @Serializable
    internal data class ReleaseMetadata(
        val schemaVersion: Int = 1,
        val packageName: String,
        val versionCode: Int,
        val versionName: String,
        val assetName: String,
        val sha256: String,
        val sizeBytes: Long,
    )

    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val cached = preferences.getString(cachedInfoKey, null)?.let { cachedJson ->
            runCatching { json.decodeFromString<UpdateInfo>(cachedJson) }.getOrNull()
        }
        val now = System.currentTimeMillis()
        if (now - preferences.getLong(lastCheckKey, 0L) < checkIntervalMs) {
            return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
        }

        try {
            val requestBuilder = Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Gameplay/${BuildConfig.VERSION_NAME}")
            preferences.getString(etagKey, null)?.let { requestBuilder.header("If-None-Match", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                preferences.edit().putLong(lastCheckKey, now).apply()

                if (response.code == 304) {
                    return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                }
                if (!response.isSuccessful) {
                    Timber.w("GitHub update check failed: HTTP ${response.code}")
                    return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                }

                response.header("ETag")?.let { etag ->
                    preferences.edit().putString(etagKey, etag).apply()
                }

                val release = response.body?.string()?.let { body ->
                    json.decodeFromString<GitHubRelease>(body)
                } ?: return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                if (release.draft || release.prerelease) return@withContext null

                val metadataAsset = release.assets.firstOrNull { it.name == metadataAssetName }
                    ?: return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                if (!isTrustedGitHubUrl(metadataAsset.browserDownloadUrl)) return@withContext null

                val metadataRequest = Request.Builder()
                    .url(metadataAsset.browserDownloadUrl)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "Gameplay/${BuildConfig.VERSION_NAME}")
                    .build()
                val metadata = httpClient.newCall(metadataRequest).execute().use { metadataResponse ->
                    if (!metadataResponse.isSuccessful) return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                    metadataResponse.body?.string()?.let { body ->
                        json.decodeFromString<ReleaseMetadata>(body)
                    }
                } ?: return@withContext cached?.takeIf(UpdateInfo::updateAvailable)

                val apkAsset = release.assets.firstOrNull { it.name == metadata.assetName }
                    ?: return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
                if (!isCompatibleReleaseMetadata(metadata) || !isTrustedGitHubUrl(apkAsset.browserDownloadUrl)) {
                    Timber.w("GitHub release metadata is not valid for this build")
                    return@withContext null
                }

                val updateInfo = UpdateInfo(
                    updateAvailable = true,
                    versionCode = metadata.versionCode,
                    versionName = metadata.versionName,
                    downloadUrl = apkAsset.browserDownloadUrl,
                    releaseNotes = release.body,
                    releaseTag = release.tagName,
                    packageName = metadata.packageName,
                    sha256 = metadata.sha256.lowercase(),
                    sizeBytes = metadata.sizeBytes,
                )
                preferences.edit()
                    .putString(cachedInfoKey, json.encodeToString(updateInfo))
                    .apply()
                Timber.i("GitHub update available: ${updateInfo.versionName} (${updateInfo.versionCode})")
                return@withContext updateInfo
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking GitHub releases")
            return@withContext cached?.takeIf(UpdateInfo::updateAvailable)
        }
        return@withContext null
    }

    internal fun isCompatibleReleaseMetadata(
        metadata: ReleaseMetadata,
        packageName: String = BuildConfig.APPLICATION_ID,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): Boolean =
        metadata.schemaVersion == 1 &&
            metadata.packageName == packageName &&
            metadata.versionCode > currentVersionCode &&
            metadata.sha256.matches(Regex("[A-Fa-f0-9]{64}")) &&
            metadata.sizeBytes > 0L

    internal fun isTrustedGitHubUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com"
    }.getOrDefault(false)
}

