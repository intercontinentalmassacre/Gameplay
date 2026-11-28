package app.gamenative.utils

import android.content.Context
import android.net.Uri
import app.gamenative.R
import app.gamenative.service.SteamService
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ManifestInstallResult(
    val success: Boolean,
    val message: String,
)

object ManifestInstaller {
    internal fun downloadUrls(entry: ManifestEntry): List<String> {
        val urls = (entry.urls + entry.url).filter(String::isNotBlank)
        val cdn = "https://downloads.gamenative.app/"
        val mirror = "https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/"
        return (urls + urls.filter { it.startsWith(cdn) }.map { mirror + it.removePrefix(cdn) }).distinct()
    }

    internal fun artifactDigest(entry: ManifestEntry): MessageDigest? =
        entry.sha256?.takeIf(String::isNotBlank)?.let { MessageDigest.getInstance("SHA-256") }

    internal fun validateDownloadedArtifact(file: File, entry: ManifestEntry, digest: MessageDigest? = null) {
        val size = file.length()
        check(file.isFile && size > 0L) { "downloaded artifact is empty" }
        entry.sizeBytes?.let { expected ->
            check(size == expected) { "artifact size mismatch: expected $expected, got $size" }
        }
        entry.sha256?.takeIf(String::isNotBlank)?.let { expected ->
            val checksum = digest ?: MessageDigest.getInstance("SHA-256").also { cachedDigest ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        cachedDigest.update(buffer, 0, read)
                    }
                }
            }
            val actual = checksum.digest().joinToString("") { byte -> "%02x".format(byte) }
            check(actual.equals(expected, ignoreCase = true)) { "artifact SHA-256 mismatch" }
        }
    }

    private suspend fun fetchVerifiedArtifact(
        entry: ManifestEntry,
        destination: File,
        onProgress: (Float) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (!entry.sha256.isNullOrBlank() && destination.isFile &&
            runCatching { validateDownloadedArtifact(destination, entry) }.isSuccess
        ) {
            onProgress(1f)
            return
        }
        var lastError: Exception? = null
        for (url in downloadUrls(entry)) {
            val partial = File.createTempFile("component-", ".partial", destination.parentFile)
            try {
                val digest = artifactDigest(entry)
                SteamService.fetchFile(url, partial, digest, onProgress)
                validateDownloadedArtifact(partial, entry, digest)
                coroutineContext.ensureActive()
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                Timber.w(error, "ManifestInstaller: mirror failed for %s", url)
            } finally {
                partial.delete()
            }
        }
        throw IOException("All component download URLs failed for ${entry.id}", lastError)
    }

    suspend fun downloadAndInstallDriver(
        context: Context,
        entry: ManifestEntry,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            fetchVerifiedArtifact(entry, destFile, onProgress)
            val uri = Uri.fromFile(destFile)
            val name = AdrenotoolsManager(context).installDriver(uri)
            if (name.isEmpty()) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }
            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: driver install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    /**
     * Shared helper to install a single manifest entry (driver or content).
     *
     * UI layers should provide [onProgress] to update their own state and then
     * handle the returned [ManifestInstallResult] (e.g. to show a Toast or
     * refresh installed-content lists).
     */
    suspend fun installManifestEntry(
        context: Context,
        entry: ManifestEntry,
        isDriver: Boolean,
        contentType: ContentProfile.ContentType? = null,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult {
        return if (isDriver) {
            downloadAndInstallDriver(context, entry, onProgress)
        } else {
            val type = contentType
                ?: throw IllegalArgumentException("contentType must be provided when installing manifest content")
            downloadAndInstallContent(context, entry, type, onProgress)
        }
    }

    /**
     * dxvk/vkd3d distributed as raw .tzst archives have no ContentProfile; they are extracted
     * directly into the wine prefix at launch. "Installing" one just means caching the .tzst in
     * the DXWrapperDownloader cache dir so the launch path finds it instead of downloading.
     */
    private fun isTzstEntry(entry: ManifestEntry): Boolean =
        entry.archiveFormat == "tzst" || downloadUrls(entry).first().endsWith(".tzst")

    private suspend fun installTzstToCache(
        context: Context,
        entry: ManifestEntry,
        onProgress: (Float) -> Unit,
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        try {
            val cacheDir = File(context.filesDir, "assets/dxwrapper")
            cacheDir.mkdirs()
            val dest = File(cacheDir, entry.url.substringAfterLast("/"))
            fetchVerifiedArtifact(entry, dest, onProgress)
            ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: tzst install failed")
            ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        }
    }

    suspend fun downloadAndInstallContent(
        context: Context,
        entry: ManifestEntry,
        expectedType: ContentProfile.ContentType,
        onProgress: (Float) -> Unit = {},
    ): ManifestInstallResult = withContext(Dispatchers.IO) {
        if (isTzstEntry(entry)) {
            return@withContext installTzstToCache(context, entry, onProgress)
        }
        var destFile: File? = null
        try {
            destFile = File(context.cacheDir, entry.url.substringAfterLast("/"))
            fetchVerifiedArtifact(entry, destFile, onProgress)
            val uri = Uri.fromFile(destFile)
            val mgr = ContentsManager(context)

            val (profile, fail, error) = extractContent(mgr, uri)
            if (profile == null) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }

            val installed = finishInstall(mgr, profile)
            if (!installed) {
                return@withContext ManifestInstallResult(
                    success = false,
                    message = context.getString(R.string.manifest_install_failed, entry.name),
                )
            }

            return@withContext ManifestInstallResult(
                success = true,
                message = context.getString(R.string.manifest_install_success, entry.name),
            )
        } catch (e: Exception) {
            Timber.e(e, "ManifestInstaller: content install failed")
            return@withContext ManifestInstallResult(
                success = false,
                message = context.getString(R.string.manifest_download_failed, e.message ?: e.javaClass.simpleName),
            )
        } finally {
            destFile?.delete()
        }
    }

    suspend fun ensurePocketDxvk(context: Context) = withContext(Dispatchers.IO) {
        val manager = ContentsManager(context)
        if (manager.getProfileByEntryName("dxvk-1.11.1-sarek-pocket-d3d9-1") != null) return@withContext
        val file = File.createTempFile("pocket-dxvk-", ".wcp", context.cacheDir)
        try {
            context.assets.open("Pocket-System-DX9.wcp").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val (profile, reason, error) = extractContent(manager, Uri.fromFile(file))
            checkNotNull(profile) { error?.message ?: "Pocket DXVK: $reason" }
            check(finishInstall(manager, profile)) { "Pocket DXVK installation failed" }
        } finally {
            file.delete()
        }
    }

    private suspend fun extractContent(
        mgr: ContentsManager,
        uri: Uri,
    ): Triple<ContentProfile?, ContentsManager.InstallFailedReason?, Exception?> = withContext(Dispatchers.IO) {
        var profile: ContentProfile? = null
        var failReason: ContentsManager.InstallFailedReason? = null
        var err: Exception? = null
        val latch = CountDownLatch(1)
        try {
            mgr.extraContentFile(uri, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    failReason = reason
                    err = e
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    profile = profileArg
                    latch.countDown()
                }
            })
        } catch (e: Exception) {
            err = e
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            err = Exception("Installation timed out")
        }
        Triple(profile, failReason, err)
    }

    private suspend fun finishInstall(
        mgr: ContentsManager,
        profile: ContentProfile,
    ): Boolean = withContext(Dispatchers.IO) {
        var success = false
        val latch = CountDownLatch(1)
        try {
            mgr.finishInstallContent(profile, object : ContentsManager.OnInstallFinishedCallback {
                override fun onFailed(reason: ContentsManager.InstallFailedReason, e: Exception?) {
                    latch.countDown()
                }

                override fun onSucceed(profileArg: ContentProfile) {
                    success = true
                    latch.countDown()
                }
            })
        } catch (_: Exception) {
            latch.countDown()
        }
        if (!latch.await(240, TimeUnit.SECONDS)) {
            Timber.w("ManifestInstaller: finishInstall timed out after 240 seconds")
            return@withContext false
        }
        return@withContext success
    }
}
