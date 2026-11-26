package app.gamenative.itch

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end acceptance over a local HTTP server and tiny ZIP fixtures, with no
 * itch.io account involved:
 * library → upload choice → download → install → "launch" → restart offline →
 * detect second build of the same upload → update → same card/save → failed
 * update leaves the working version launchable.
 */
class ItchFlowAcceptanceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var api: ItchApiClient
    private lateinit var downloader: ItchResumableDownloader

    private val token = "test-token"
    private val gameId = 341190L
    private val uploadId = 700L
    private var zipV1 = ByteArray(0)
    private var zipV2 = ByteArray(0)
    private var phase = 1
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        api = ItchApiClient(
            http = http,
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            authorizeBaseUrl = server.url("/").toString().trimEnd('/'),
        )
        downloader = ItchResumableDownloader(http)
        zipV1 = zip(
            ".itch.toml" to text(
                """
                [[actions]]
                name = "play"
                path = "Game.exe"
                platform = "windows"
                args = ["--save-dir", "saves"]
                """.trimIndent(),
            ),
            "Game.exe" to text("v1"),
            "obsolete.bin" to text("old"),
            "content/pack.bin" to ByteArray(2048) { (it % 200).toByte() },
        )
        zipV2 = zip(
            ".itch.toml" to text(
                """
                [[actions]]
                name = "play"
                path = "Game.exe"
                platform = "windows"
                args = ["--save-dir", "saves"]
                """.trimIndent(),
            ),
            "Game.exe" to text("v2"),
            "content/pack.bin" to ByteArray(2048) { (it % 200).toByte() },
            "content/new-levels.bin" to text("new"),
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/profile/owned-keys") -> ownedKeysPage(request)
                    path.startsWith("/games/$gameId/uploads") -> uploadsResponse()
                    path.startsWith("/uploads/$uploadId/download") -> {
                        val file = if (phase == 1) "v1.zip" else "v2.zip"
                        MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"url":"${server.url("/cdn/$file")}"}""")
                    }
                    path.startsWith("/cdn/v1.zip") -> zipResponse(zipV1, "\"build-500\"")
                    path.startsWith("/cdn/v2.zip") -> zipResponse(zipV2, "\"build-501\"")
                    path.startsWith("/cdn/corrupt.zip") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("ETag", "\"build-502\"")
                        .setBody(Buffer().write(ByteArray(1024) { (it * 13 % 255).toByte() }))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After
    fun tearDown() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private fun text(value: String) = value.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { stream ->
            entries.forEach { (name, bytes) ->
                stream.putNextEntry(ZipEntry(name))
                stream.write(bytes)
                stream.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun zipResponse(bytes: ByteArray, etag: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("ETag", etag)
        .setHeader("Accept-Ranges", "bytes")
        .setBody(Buffer().write(bytes))

    private fun ownedKeysPage(request: RecordedRequest): MockResponse {
        val page = request.requestUrl?.queryParameter("page")?.toIntOrNull() ?: 1
        val body = if (page == 1) {
            """
            {"page":1,"per_page":100,"owned_keys":[
              {"id":1,"game_id":$gameId,"created_at":"2024-01-01T00:00:00Z",
               "game":{"id":$gameId,"title":"Pikuniku","url":"https://devolver.itch.io/pikuniku",
                       "cover_url":"https://img.itch.zone/pikuniku.jpg","traits":["p_windows"],
                       "user":{"username":"DevolverDigital","display_name":"Devolver Digital"}}},
              {"id":2,"game_id":42,"created_at":"2024-01-01T00:00:00Z",
               "game":{"id":42,"title":"Web Game","traits":["p_web"]}}
            ]}
            """.trimIndent()
        } else {
            """{"page":$page,"per_page":100,"owned_keys":[]}"""
        }
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun uploadsResponse(): MockResponse {
        val windowsBuildId = if (phase == 1) 500 else 501
        val windowsSize = if (phase == 1) zipV1.size else zipV2.size
        val updatedAt = if (phase == 1) "2026-01-01T00:00:00Z" else "2026-02-01T00:00:00Z"
        val userVersion = if (phase == 1) "1.0.0" else "1.1.0"
        return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(
            """
            {"uploads":[
              {"id":701,"filename":"pikuniku-linux.zip","size":100,"type":"default",
               "channel_name":"linux","platforms":{"linux":true},"position":1},
              {"id":702,"filename":"pikuniku-ost.zip","size":50,"type":"soundtrack",
               "platforms":{"windows":true},"position":2},
              {"id":703,"filename":"pikuniku-demo-win.zip","display_name":"Demo build","size":100,
               "type":"default","platforms":{"windows":true},"position":3},
              {"id":704,"filename":"pikuniku-editor.zip","display_name":"Level editor","size":100,
               "type":"default","platforms":{"windows":true},"position":4},
              {"id":$uploadId,"filename":"pikuniku-win64.zip","display_name":"Pikuniku (Windows)",
               "size":$windowsSize,"type":"default","channel_name":"windows",
               "platforms":{"windows":true},"updated_at":"$updatedAt","position":5,
               "build":{"id":$windowsBuildId,"version":$windowsBuildId,"user_version":"$userVersion"}}
            ]}
            """.trimIndent(),
        )
    }

    private fun downloadTo(target: File, upload: ItchUpload): ItchResumableDownloader.Result = runBlocking {
        downloader.download(
            target = target,
            expectedUploadId = upload.id,
            expectedSize = upload.size,
        ) {
            ItchDownloadSource(
                url = api.requestDownloadUrl(token, upload.id),
                uploadId = upload.id,
            )
        }
    }

    private suspend fun install(
        download: ItchResumableDownloader.Result,
        layout: ItchInstallLayout,
        upload: ItchUpload,
        versionId: String,
    ): ItchVersionMeta {
        val root = ItchInstaller.extractArchive(download.file, layout.stagingDir(versionId))
        val resolution = ItchInstaller.resolveLaunch(root)
        assertTrue("expected a single launch target, got $resolution", resolution is ItchLaunchResolution.Selected)
        val spec = (resolution as ItchLaunchResolution.Selected).spec
        assertEquals(listOf("--save-dir", "saves"), spec.args)
        return ItchInstaller.commitVersion(
            gameRoot = root,
            layout = layout,
            meta = ItchVersionMeta(
                versionId = versionId,
                uploadId = upload.id,
                buildId = upload.build?.id,
                userVersion = upload.build?.userVersion,
                fileUpdatedAt = upload.updatedAt,
                fileSizeBytes = upload.size.toLong(),
                launchTarget = spec.target,
                launchArgs = spec.args,
            ),
        )
    }

    @Test
    fun `library to install to update keeps one card and the player save`() = runBlocking {
        // ── 1. Library: paginated owned keys.
        val keys = api.fetchAllOwnedKeys(token, perPage = 100)
        assertEquals(2, keys.size)
        val game = keys.first { it.gameId == gameId }.game!!
        val appId = "ITCH_${game.id}"
        assertEquals("ITCH_341190", appId)
        assertEquals("Pikuniku", game.title)
        assertEquals("Devolver Digital", game.user?.displayName)

        // ── 2. Upload choice: Windows game wins over demo/editor/soundtrack/linux.
        val uploadsV1 = api.fetchUploads(token, gameId)
        val choice = ItchUploadSelector.choose(uploadsV1)
        assertTrue(choice is ItchUploadChoice.Single)
        val selected = (choice as ItchUploadChoice.Single).upload
        assertEquals(uploadId, selected.id)
        assertEquals("Pikuniku (Windows)", selected.title)
        assertEquals("1.0.0", selected.versionLabel)

        // ── 3. Download and install.
        val layout = ItchInstaller.layout(File(temporaryFolder.root, "drive_c/itch/$gameId"))
        val firstDownload = downloadTo(File(temporaryFolder.root, "downloads/700.zip"), uploadsV1.first { it.id == uploadId })
        val uploadV1 = uploadsV1.first { it.id == uploadId }
        assertEquals(uploadV1.size, firstDownload.bytes)
        val v1 = install(firstDownload, layout, uploadV1, ItchInstaller.newVersionId(uploadId, 500L, 1_000L))

        var containerTarget = ""
        val stateV1 = ItchInstaller.activate(layout, v1) {
            containerTarget = "itch/$gameId/versions/${v1.versionId}/${v1.launchTarget}"
        }
        assertEquals(v1.versionId, stateV1.activeVersionId)
        assertEquals(v1.versionId, containerTarget.substringAfter("versions/").substringBefore('/'))
        assertTrue(File(layout.versionDir(v1.versionId), "Game.exe").isFile)

        // Player creates a save next to the executable.
        val saveFile = File(layout.versionDir(v1.versionId), "saves/slot1.sav")
        saveFile.parentFile!!.mkdirs()
        saveFile.writeText("chapter=3")

        // ── 4. App restart, offline: no itch.io request is needed to launch.
        val requestsBeforeRestart = requests.size
        val recoveredState = ItchInstaller.readState(layout)
        assertEquals(v1.versionId, recoveredState!!.activeVersionId)
        val restoredMeta = ItchInstaller.readMeta(layout, recoveredState.activeVersionId)!!
        assertTrue(ItchInstaller.verifyVersion(layout, restoredMeta))
        assertTrue(File(layout.versionDir(recoveredState.activeVersionId), recoveredState.launchTarget).isFile)
        assertEquals("chapter=3", File(layout.versionDir(recoveredState.activeVersionId), "saves/slot1.sav").readText())
        assertEquals(requestsBeforeRestart, requests.size)

        // ── 5. Update detection for the same upload (second butler build).
        phase = 2
        val uploadsV2 = api.fetchUploads(token, gameId)
        val installedRevision = ItchUpdateChecker.revisionFor(ItchInstallerKind.ZIP, uploadV1)
        val decision = ItchUpdateChecker.evaluate(installedRevision, uploadsV2)
        assertEquals(ItchUpdateStatus.UPDATE_AVAILABLE, decision.status)
        assertEquals(ItchUpdateReason.BUILD_CHANGED, decision.reason)
        assertEquals(uploadId, decision.upload?.id)

        // ── 6. Download + stage the update without touching the working install.
        val secondDownload = downloadTo(File(temporaryFolder.root, "downloads/700-v2.zip"), decision.upload!!)
        val v2 = install(secondDownload, layout, decision.upload!!, ItchInstaller.newVersionId(uploadId, 501L, 2_000L))
        // Old version is still the active one until activation.
        assertEquals(v1.versionId, ItchInstaller.readState(layout)?.activeVersionId)
        assertTrue(File(layout.versionDir(v1.versionId), "Game.exe").readText() == "v1")

        // ── 7. Player files are carried over, obsolete delivery files are gone.
        val carry = ItchInstaller.carryOverPlayerFiles(
            oldVersionDir = layout.versionDir(v1.versionId),
            oldMeta = v1,
            newVersionDir = layout.versionDir(v2.versionId),
            newMeta = v2,
        )
        assertEquals(1, carry.copied)
        val stateV2 = ItchInstaller.activate(layout, v2) {
            containerTarget = "itch/$gameId/versions/${v2.versionId}/${v2.launchTarget}"
        }

        // ── 8. Same card/appId, new build, previous save still there.
        assertEquals("ITCH_${game.id}", appId)
        assertEquals(uploadId, v2.uploadId)
        assertEquals(v2.versionId, stateV2.activeVersionId)
        val activeDir = layout.versionDir(stateV2.activeVersionId)
        assertEquals("v2", File(activeDir, "Game.exe").readText())
        assertEquals("chapter=3", File(activeDir, "saves/slot1.sav").readText())
        assertFalse(File(activeDir, "obsolete.bin").exists())
        assertTrue(File(activeDir, "content/new-levels.bin").isFile)
        assertTrue(File(layout.versionDir(v1.versionId), "Game.exe").isFile)

        // ── 9. A failed update leaves the current version launchable.
        val failedArchive = File(temporaryFolder.root, "downloads/corrupt.zip")
        val corrupt = downloader.download(
            target = failedArchive,
            expectedUploadId = uploadId,
            expectedSize = 1024,
        ) {
            ItchDownloadSource(
                url = api.requestDownloadUrl(token, uploadId).let { server.url("/cdn/corrupt.zip").toString() },
                uploadId = uploadId,
            )
        }
        assertEquals(1024L, corrupt.bytes)
        try {
            ItchInstaller.extractArchive(corrupt.file, layout.stagingDir("u700_b502"))
            fail("expected the corrupt archive to be rejected")
        } catch (expected: Exception) {
            // expected
        }
        assertEquals(v2.versionId, ItchInstaller.readState(layout)?.activeVersionId)
        assertTrue(File(layout.versionDir(v2.versionId), "Game.exe").isFile)
        assertEquals("chapter=3", File(layout.versionDir(v2.versionId), "saves/slot1.sav").readText())
        assertFalse(layout.versionDir("u700_b502").exists())
    }

    @Test
    fun `access denied on the library surfaces without breaking other sources`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return MockResponse().setResponseCode(401).setBody("""{"errors":["invalid token"]}""")
            }
        }
        try {
            api.fetchAllOwnedKeys("expired-token")
            fail("expected ItchApiException")
        } catch (error: ItchApiException) {
            assertEquals(ItchApiException.Kind.UNAUTHORIZED, error.kind)
        }
    }
}
