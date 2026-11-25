package app.gamenative.itch

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ItchResumableDownloaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var downloader: ItchResumableDownloader
    private val requests = mutableListOf<RecordedRequest>()
    private val content = ByteArray(4096) { (it % 251).toByte() }
    private val etag = "build-1"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        downloader = ItchResumableDownloader(http)
    }

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun dispatcher(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
    }

    private fun fullResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setHeader("Accept-Ranges", "bytes")
        .setBody(Buffer().write(content))

    private fun partialResponse(start: Long): MockResponse {
        val slice = content.copyOfRange(start.toInt(), content.size)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("ETag", etag)
            .setHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
            .setBody(Buffer().write(slice))
    }

    private suspend fun source(url: String = server.url("/cdn/game.zip").toString(), uploadId: Long = 7) =
        ItchDownloadSource(url = url, uploadId = uploadId)

    @Test
    fun `downloads a full file without sending authorization to the cdn`() = runBlocking {
        dispatcher { fullResponse() }
        val target = File(temporaryFolder.root, "game.zip")

        val result = downloader.download(
            target = target,
            expectedUploadId = 7,
            expectedSize = content.size.toLong(),
        ) { source() }

        assertEquals(content.size.toLong(), result.bytes)
        assertEquals(0L, result.resumedFrom)
        assertArrayEquals(content, target.readBytes())
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.getHeader("Authorization") == null })
        assertFalse(File(target.absolutePath + ".part").exists())
    }

    @Test
    fun `resumes a partial file only when the entity and range match`() = runBlocking {
        val target = File(temporaryFolder.root, "game.zip")
        val part = File(target.absolutePath + ".part")
        part.writeBytes(content.copyOfRange(0, 1024))
        File(target.absolutePath + ".part.json").writeText(
            """{"uploadId":7,"url":"old","etag":"$etag","totalBytes":${content.size}}""",
        )
        dispatcher { request ->
            val range = request.getHeader("Range")
            if (range != null) {
                partialResponse(range.removePrefix("bytes=").removeSuffix("-").toLong())
            } else {
                fullResponse()
            }
        }

        val result = downloader.download(
            target = target,
            expectedUploadId = 7,
            expectedSize = content.size.toLong(),
        ) { source(url = server.url("/cdn/game.zip?mint=2").toString()) }

        assertEquals(1024L, result.resumedFrom)
        assertEquals(content.size.toLong(), result.bytes)
        assertArrayEquals(content, target.readBytes())
        assertEquals("bytes=1024-", requests.single().getHeader("Range"))
    }

    @Test
    fun `validator mismatch restarts instead of appending to another version`() = runBlocking {
        val target = File(temporaryFolder.root, "game.zip")
        File(target.absolutePath + ".part").writeBytes(ByteArray(512) { 9 })
        File(target.absolutePath + ".part.json").writeText(
            """{"uploadId":7,"url":"old","etag":"\"stale\"","totalBytes":${content.size}}""",
        )
        dispatcher { request ->
            // Server ignores the stale range and returns a new full entity.
            if (request.getHeader("Range") != null) fullResponse() else fullResponse()
        }

        val result = downloader.download(
            target = target,
            expectedUploadId = 7,
            expectedSize = content.size.toLong(),
        ) { source() }

        assertEquals(0L, result.resumedFrom)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `expired temporary link is refreshed for the same upload`() = runBlocking {
        var minted = 0
        dispatcher { request ->
            when {
                request.path!!.startsWith("/cdn/") -> {
                    val mint = request.requestUrl!!.queryParameter("mint")!!.toInt()
                    if (mint == 1) {
                        MockResponse().setResponseCode(403).setBody("expired")
                    } else {
                        fullResponse()
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val target = File(temporaryFolder.root, "game.zip")

        val result = downloader.download(
            target = target,
            expectedUploadId = 7,
            expectedSize = content.size.toLong(),
        ) {
            minted += 1
            source(url = server.url("/cdn/game.zip?mint=$minted").toString(), uploadId = 7)
        }

        assertEquals(2, minted)
        assertTrue(result.refreshedUrl)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `a second refusal after refresh is an expired link failure`() = runBlocking {
        dispatcher { MockResponse().setResponseCode(403).setBody("expired") }
        val target = File(temporaryFolder.root, "game.zip")
        try {
            downloader.download(target, 7) { source() }
            fail("expected ItchDownloadException")
        } catch (error: ItchDownloadException) {
            assertEquals(ItchDownloadException.Kind.EXPIRED_LINK, error.kind)
        }
    }

    @Test
    fun `partial file belonging to a different upload is discarded`() = runBlocking {
        val target = File(temporaryFolder.root, "game.zip")
        File(target.absolutePath + ".part").writeBytes(ByteArray(100) { 1 })
        File(target.absolutePath + ".part.json").writeText(
            """{"uploadId":99,"url":"other","etag":"$etag","totalBytes":${content.size}}""",
        )
        dispatcher { request ->
            // No Range header may be sent for a different upload.
            assertTrue(request.getHeader("Range") == null)
            fullResponse()
        }
        val result = downloader.download(target, 7) { source() }
        assertEquals(0L, result.resumedFrom)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `server ignoring range restarts the transfer`() = runBlocking {
        val target = File(temporaryFolder.root, "game.zip")
        File(target.absolutePath + ".part").writeBytes(content.copyOfRange(0, 2048))
        File(target.absolutePath + ".part.json").writeText(
            """{"uploadId":7,"url":"old","etag":"$etag","totalBytes":${content.size}}""",
        )
        dispatcher { fullResponse() }
        val result = downloader.download(target, 7) { source() }
        assertEquals(0L, result.resumedFrom)
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun `size mismatch fails and removes the bad download`() = runBlocking {
        dispatcher { fullResponse() }
        val target = File(temporaryFolder.root, "game.zip")
        try {
            downloader.download(target, 7, expectedSize = content.size + 10L) { source() }
            fail("expected ItchDownloadException")
        } catch (error: ItchDownloadException) {
            assertEquals(ItchDownloadException.Kind.SIZE_MISMATCH, error.kind)
        }
        assertFalse(target.exists())
        assertFalse(File(target.absolutePath + ".part").exists())
    }

    @Test
    fun `progress reports final bytes and total`() = runBlocking {
        dispatcher { fullResponse() }
        val target = File(temporaryFolder.root, "game.zip")
        var lastDownloaded = 0L
        var lastTotal = 0L
        downloader.download(
            target = target,
            expectedUploadId = 7,
            progress = { downloaded, total ->
                lastDownloaded = downloaded
                lastTotal = total
            },
        ) { source() }
        assertEquals(content.size.toLong(), lastDownloaded)
        assertEquals(content.size.toLong(), lastTotal)
    }
}
