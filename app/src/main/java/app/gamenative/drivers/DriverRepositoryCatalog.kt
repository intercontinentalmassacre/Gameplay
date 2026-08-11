package app.gamenative.drivers

import app.gamenative.utils.Net
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

data class DriverRepository(
    val id: String,
    val name: String,
    val apiUrl: String,
    val description: String,
)

data class RemoteDriverPackage(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val repositoryName: String,
)

/**
 * Trusted driver sources shipped with Gameplay.
 *
 * Repositories are queried only when selected, which avoids exhausting GitHub's
 * anonymous API allowance and keeps the driver manager responsive on launch.
 */
object DriverRepositoryCatalog {
    val trustedRepositories: List<DriverRepository> = listOf(
        DriverRepository(
            id = "stevenmxz-adrenotools",
            name = "StevenMXZ AdrenoTools",
            apiUrl = "https://api.github.com/repos/StevenMXZ/Adreno-Tools-Drivers/releases",
            description = "AdrenoTools-packaged Turnip builds, published on a regular schedule.",
        ),
        DriverRepository(
            id = "whitebelyash-adrenotools",
            name = "Whitebelyash AdrenoTools",
            apiUrl = "https://api.github.com/repos/whitebelyash/AdrenoToolsDrivers/releases",
            description = "AdrenoTools-compatible stable and experimental packages.",
        ),
        DriverRepository(
            id = "weab-chan-turnip",
            name = "Weab-Chan Turnip",
            apiUrl = "https://api.github.com/repos/Weab-chan/freedreno_turnip-CI/releases",
            description = "Alternative Turnip builds for compatibility testing.",
        ),
        DriverRepository(
            id = "purple-turnip",
            name = "Purple Turnip",
            apiUrl = "https://api.github.com/repos/MrPurple666/purple-turnip/releases",
            description = "Community Turnip builds for compatibility testing.",
        ),
        DriverRepository(
            id = "zoerakk-qualcomm",
            name = "Zoerakk Qualcomm Adreno",
            apiUrl = "https://api.github.com/repos/zoerakk/qualcomm-adreno-driver/releases",
            description = "Qualcomm Adreno packages; only AdrenoTools-compatible ZIP assets are shown.",
        ),
    )

    suspend fun fetchPackages(repository: DriverRepository): List<RemoteDriverPackage> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(repository.apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub returned HTTP ${response.code}")
                }
                val payload = response.body?.string().orEmpty()
                if (payload.isBlank()) throw IOException("Repository returned an empty response")

                parsePackages(repository, payload)
            }
        }

    internal fun parsePackages(
        repository: DriverRepository,
        payload: String,
    ): List<RemoteDriverPackage> {
        val releases = Json.parseToJsonElement(payload).jsonArray
        return releases
            .asSequence()
            .take(16)
            .filter { release ->
                val objectValue = release.jsonObject
                objectValue["draft"]?.jsonPrimitive?.booleanOrNull != true
            }
            .flatMap { release ->
                val objectValue = release.jsonObject
                val releaseName = objectValue["name"]?.jsonPrimitive?.contentOrNull
                    ?: objectValue["tag_name"]?.jsonPrimitive?.contentOrNull
                    ?: "Driver"
                objectValue["assets"]?.jsonArray.orEmpty().asSequence().mapNotNull { asset ->
                    val assetValue = asset.jsonObject
                    val fileName = assetValue["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    if (!fileName.endsWith(".zip", ignoreCase = true)) return@mapNotNull null
                    val downloadUrl = assetValue["browser_download_url"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: return@mapNotNull null
                    val assetId = assetValue["id"]?.jsonPrimitive?.contentOrNull
                        ?: "$releaseName:$fileName"
                    RemoteDriverPackage(
                        id = "${repository.id}:$assetId",
                        displayName = "$releaseName — $fileName",
                        fileName = fileName,
                        downloadUrl = downloadUrl,
                        sizeBytes = assetValue["size"]?.jsonPrimitive?.longOrNull ?: -1L,
                        repositoryName = repository.name,
                    )
                }
            }
            .distinctBy { it.downloadUrl }
            .toList()
    }

    suspend fun downloadPackage(
        driverPackage: RemoteDriverPackage,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(driverPackage.downloadUrl).build()
        Net.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Driver download returned HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Driver download returned no data")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: driverPackage.sizeBytes
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var downloadedBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
        }
    }
}
