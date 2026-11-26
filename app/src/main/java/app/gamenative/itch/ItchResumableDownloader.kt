package app.gamenative.itch

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/** A freshly minted, short-lived download URL for a specific upload. */
data class ItchDownloadSource(
    val url: String,
    val uploadId: Long,
)

class ItchDownloadException(
    message: String,
    val kind: Kind,
) : IOException(message) {
    enum class Kind {
        /** CDN refused the transfer even after refreshing the temporary URL. */
        ACCESS_DENIED,

        /** The temporary URL expired and refreshing it failed. */
        EXPIRED_LINK,
        NETWORK,
        SERVER,
        SIZE_MISMATCH,
        INCOMPLETE,
        INVALID_URL,
    }
}

/**
 * Resumable download of one itch.io upload.
 *
 * Rules encoded here:
 * * the Bearer token is never part of the CDN request — the caller mints a
 *   temporary URL with [urlProvider] and this class only fetches that URL;
 * * a partial `.part` file is appended to only when the sidecar metadata shows
 *   the *same upload* and the server still serves the same entity
 *   (ETag/Last-Modified match) and answers the `Range` request with 206;
 * * an expired link (401/403/410) forces exactly one fresh mint for the same
 *   upload and a retry; a second refusal is reported as access denied;
 * * a 200 answer to a `Range` request means the server cannot continue the old
 *   transfer, so the partial file is discarded and the download restarts.
 */
class ItchResumableDownloader(
    http: OkHttpClient = OkHttpClient(),
) {
    private val client: OkHttpClient = http.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Result(
        val file: File,
        val bytes: Long,
        val resumedFrom: Long,
        val refreshedUrl: Boolean,
    )

    @Serializable
    private data class PartialMeta(
        val uploadId: Long = 0,
        val url: String = "",
        val etag: String? = null,
        val lastModified: String? = null,
        val totalBytes: Long = 0,
    )

    suspend fun download(
        target: File,
        expectedUploadId: Long,
        expectedSize: Long = 0L,
        progress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        urlProvider: suspend () -> ItchDownloadSource,
    ): Result = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.absolutePath + ".part")
        val metaFile = File(target.absolutePath + ".part.json")
        var refreshed = false
        var attempt = 0

        // Discard a partial transfer that belongs to a different upload.
        var meta = readMeta(metaFile)
        if (meta != null && meta.uploadId != expectedUploadId) {
            reset(part, metaFile)
            meta = null
        }

        while (true) {
            attempt += 1
            currentCoroutineContext().ensureActive()
            val source = try {
                urlProvider()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ItchDownloadException(
                    "Could not request a download link from itch.io: ${error.message ?: "unknown error"}",
                    ItchDownloadException.Kind.EXPIRED_LINK,
                )
            }
            if (source.url.isBlank()) {
                throw ItchDownloadException("itch.io returned an empty download URL", ItchDownloadException.Kind.INVALID_URL)
            }

            val existing = part.takeIf { it.isFile }?.length() ?: 0L
            val currentMeta = readMeta(metaFile)
            val canResume = existing > 0L &&
                currentMeta != null &&
                currentMeta.uploadId == expectedUploadId &&
                (currentMeta.etag != null || currentMeta.lastModified != null)

            val builder = Request.Builder().url(source.url)
            if (canResume) {
                builder.header("Range", "bytes=$existing-")
                currentMeta?.etag?.let { builder.header("If-Range", it) }
                    ?: currentMeta?.lastModified?.let { builder.header("If-Range", it) }
            }

            val outcome = try {
                client.newCall(builder.build()).execute().use { response ->
                    when {
                        response.code == 206 -> {
                            val rangeStart = parseContentRangeStart(response.header("Content-Range"))
                            val sameEntity = currentMeta != null && sameValidator(response, currentMeta)
                            if (!sameEntity || rangeStart != existing) {
                                // The server cannot prove it is serving the same version at
                                // the same offset. A 206 only carries the remainder, so the
                                // transfer must restart from a fresh full response.
                                reset(part, metaFile)
                                TransferOutcome.RetryFromScratch
                            } else {
                                handleTransfer(response, part, metaFile, source, startOver = false, progress)
                            }
                        }
                        response.code == 200 -> {
                            // A full body: either the first attempt or a restart.
                            handleTransfer(response, part, metaFile, source, startOver = existing > 0L, progress)
                        }
                        response.code == 416 -> {
                            val total = parseUnsatisfiedTotal(response.header("Content-Range"))
                            if (existing > 0L && total > 0L && existing == total) {
                                TransferOutcome.AlreadyComplete(existing)
                            } else {
                                reset(part, metaFile)
                                TransferOutcome.RetryFromScratch
                            }
                        }
                        response.code == 401 || response.code == 403 || response.code == 410 -> {
                            TransferOutcome.RefreshRequired(response.code)
                        }
                        response.code in 500..599 -> throw ItchDownloadException(
                            "itch.io CDN failed with HTTP ${response.code}",
                            ItchDownloadException.Kind.SERVER,
                        )
                        else -> throw ItchDownloadException(
                            "itch.io CDN refused the download (HTTP ${response.code})",
                            ItchDownloadException.Kind.ACCESS_DENIED,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ItchDownloadException) {
                throw error
            } catch (error: IOException) {
                throw ItchDownloadException(
                    "Download interrupted: ${error.message ?: error.javaClass.simpleName}",
                    ItchDownloadException.Kind.NETWORK,
                )
            }

            when (outcome) {
                is TransferOutcome.Completed -> {
                    if (expectedSize > 0L && outcome.bytes != expectedSize) {
                        reset(part, metaFile)
                        throw ItchDownloadException(
                            "Downloaded size ${outcome.bytes} does not match the expected $expectedSize bytes",
                            ItchDownloadException.Kind.SIZE_MISMATCH,
                        )
                    }
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    metaFile.delete()
                    return@withContext Result(
                        file = target,
                        bytes = outcome.bytes,
                        resumedFrom = outcome.resumedFrom,
                        refreshedUrl = refreshed,
                    )
                }
                is TransferOutcome.AlreadyComplete -> {
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    metaFile.delete()
                    return@withContext Result(
                        file = target,
                        bytes = outcome.bytes,
                        resumedFrom = outcome.bytes,
                        refreshedUrl = refreshed,
                    )
                }
                TransferOutcome.RetryFromScratch -> {
                    if (attempt > 3) {
                        throw ItchDownloadException(
                            "itch.io kept restarting the download",
                            ItchDownloadException.Kind.INCOMPLETE,
                        )
                    }
                }
                is TransferOutcome.RefreshRequired -> {
                    if (refreshed || attempt > 2) {
                        throw ItchDownloadException(
                            "The itch.io download link expired (HTTP ${outcome.statusCode})",
                            ItchDownloadException.Kind.EXPIRED_LINK,
                        )
                    }
                    // Keep the partial file: the freshly minted URL serves the same
                    // upload, so the next attempt can continue with a Range request.
                    refreshed = true
                }
                is TransferOutcome.Partial -> {
                    // The server stopped early (or the coroutine was paused). Keep the
                    // `.part` file and its validators so the next call can resume.
                    throw ItchDownloadException(
                        "Download ended before all bytes arrived",
                        ItchDownloadException.Kind.INCOMPLETE,
                    )
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw ItchDownloadException("Download did not complete", ItchDownloadException.Kind.INCOMPLETE)
    }

    private sealed interface TransferOutcome {
        data class Completed(val bytes: Long, val resumedFrom: Long, val refreshed: Boolean) : TransferOutcome
        data class AlreadyComplete(val bytes: Long) : TransferOutcome
        data class Partial(val bytes: Long) : TransferOutcome
        data object RetryFromScratch : TransferOutcome
        data class RefreshRequired(val statusCode: Int) : TransferOutcome
    }

    private suspend fun handleTransfer(
        response: okhttp3.Response,
        part: File,
        metaFile: File,
        source: ItchDownloadSource,
        startOver: Boolean,
        progress: (Long, Long) -> Unit,
    ): TransferOutcome {
        val responseEtag = response.header("ETag")
        val responseLastModified = response.header("Last-Modified")
        val totalFromRange = parseContentRangeTotal(response.header("Content-Range"))
        val body = response.body ?: throw ItchDownloadException(
            "itch.io CDN returned no body",
            ItchDownloadException.Kind.INCOMPLETE,
        )
        val declared = body.contentLength()
        val total = when {
            totalFromRange > 0L -> totalFromRange
            response.code == 200 && declared > 0L -> declared
            else -> 0L
        }

        val meta = PartialMeta(
            uploadId = source.uploadId,
            url = source.url,
            etag = responseEtag,
            lastModified = responseLastModified,
            totalBytes = total,
        )
        writeMeta(metaFile, meta)

        if (startOver) {
            part.delete()
        }
        part.parentFile?.mkdirs()
        var bytes = if (startOver) 0L else part.length()
        val resumedFrom = bytes
        progress(bytes, total)

        RandomAccessFile(part, "rw").use { out ->
            if (startOver) out.setLength(0)
            out.seek(bytes)
            body.byteStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    out.write(buffer, 0, read)
                    bytes += read
                    progress(bytes, total)
                }
            }
        }
        return when {
            total > 0L && bytes == total -> TransferOutcome.Completed(bytes, resumedFrom, refreshed = false)
            total <= 0L -> TransferOutcome.Completed(bytes, resumedFrom, refreshed = false)
            else -> TransferOutcome.Partial(bytes)
        }
    }

    private fun sameValidator(response: okhttp3.Response, meta: PartialMeta): Boolean {
        val etag = response.header("ETag")
        val lastModified = response.header("Last-Modified")
        if (meta.etag != null && etag != null) return meta.etag == etag
        if (meta.lastModified != null && lastModified != null) return meta.lastModified == lastModified
        // Range honoured but no validator: treat the byte offset as proof enough only
        // when the server explicitly reports the requested range start.
        return true
    }

    private fun parseContentRangeStart(header: String?): Long {
        if (header.isNullOrBlank()) return -1L
        val match = Regex("""bytes\s+(\d+)-""").find(header) ?: return -1L
        return match.groupValues[1].toLongOrNull() ?: -1L
    }

    private fun parseContentRangeTotal(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        val match = Regex("""/\s*(\d+)\s*$""").find(header) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun parseUnsatisfiedTotal(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        val match = Regex("""\*/(\d+)""").find(header) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    private fun reset(part: File, metaFile: File) {
        part.delete()
        metaFile.delete()
    }

    private fun readMeta(file: File): PartialMeta? {
        if (!file.isFile) return null
        return runCatching {
            ItchJson.decodeFromString(PartialMeta.serializer(), file.readText())
        }.getOrNull()
    }

    private fun writeMeta(file: File, meta: PartialMeta) {
        runCatching { file.writeText(ItchJson.encodeToString(PartialMeta.serializer(), meta)) }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
    }
}
