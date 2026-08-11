package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import app.gamenative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object UpdateInstaller {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        versionName: String,
        onProgress: (Float) -> Unit,
        expectedSha256: String? = null,
        expectedSizeBytes: Long? = null,
        expectedPackageName: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            require(isTrustedGitHubUrl(downloadUrl)) { "Update URL is not a trusted GitHub asset" }

            val safeVersionName = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val apkFileName = "gameplay-v$safeVersionName.apk"
            val destination = File(context.cacheDir, apkFileName)
            val partial = File(context.cacheDir, "$apkFileName.part")

            downloadApk(downloadUrl, partial, expectedSizeBytes, onProgress)
            destination.delete()
            require(partial.renameTo(destination)) { "Could not finalize downloaded APK" }
            verifyApk(
                context = context,
                apkFile = destination,
                expectedSha256 = expectedSha256,
                expectedSizeBytes = expectedSizeBytes,
                expectedPackageName = expectedPackageName ?: BuildConfig.APPLICATION_ID,
            )

            withContext(Dispatchers.Main) { installApk(context, destination) }
        } catch (error: Exception) {
            Timber.e(error, "Error downloading/installing Gameplay update")
            false
        }
    }

    private fun downloadApk(
        downloadUrl: String,
        destination: File,
        expectedSizeBytes: Long?,
        onProgress: (Float) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Gameplay/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "GitHub download failed: HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "GitHub returned an empty APK response" }
            val totalBytes = expectedSizeBytes ?: body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalBytes > 0L) onProgress(downloaded.toFloat() / totalBytes)
                    }
                    output.fd.sync()
                    require(downloaded > 0L) { "Downloaded APK is empty" }
                    if (expectedSizeBytes != null) {
                        require(downloaded == expectedSizeBytes) { "Downloaded APK size does not match metadata" }
                    }
                }
            }
        }
    }

    private fun verifyApk(
        context: Context,
        apkFile: File,
        expectedSha256: String?,
        expectedSizeBytes: Long?,
        expectedPackageName: String,
    ) {
        require(apkFile.isFile && apkFile.length() > 0L) { "Downloaded APK is missing or empty" }
        if (expectedSizeBytes != null) {
            require(apkFile.length() == expectedSizeBytes) { "Downloaded APK size does not match metadata" }
        }
        if (expectedSha256 != null) {
            val actualSha256 = sha256(apkFile)
            require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
                "Downloaded APK checksum mismatch"
            }
        }

        val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        requireNotNull(packageInfo) { "Downloaded file is not a readable APK" }
        require(packageInfo.packageName == expectedPackageName) {
            "Downloaded APK package does not match Gameplay"
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        require(versionCode > BuildConfig.VERSION_CODE) { "Downloaded APK is not newer than current app" }
    }

    private fun installApk(context: Context, apkFile: File): Boolean {
        return try {
            if (!apkFile.isFile) return false
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    apkFile,
                )
            } else {
                Uri.fromFile(apkFile)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
                context.grantUriPermission(
                    resolveInfo.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.startActivity(intent)
            true
        } catch (error: Exception) {
            Timber.e(error, "Error launching APK installer")
            false
        }
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun isTrustedGitHubUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com"
    }.getOrDefault(false)
}
