package app.gamenative.utils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile.ContentType
import com.winlator.contents.ContentsManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManifestIdCorrelationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun manifestDriverIdMatchesInstalledMetadataName() = runBlocking {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            zip.putNextEntry(ZipEntry("package/meta.json"))
            zip.write("""{"name":"Fixture Turnip","libraryName":"libvulkan.so"}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("package/libvulkan.so"))
            zip.write(ByteArray(64).apply {
                byteArrayOf(0x7f, 69, 76, 70, 2, 1, 1).copyInto(this)
                this[16] = 3 // ET_DYN
                this[18] = 183.toByte() // EM_AARCH64
            })
            zip.closeEntry()
        }

        MockWebServer().use { server ->
            val entry = server.manifestEntry("Fixture Turnip", "driver.zip", archive.toByteArray())
            val result = ManifestInstaller.downloadAndInstallDriver(context, entry)

            assertTrue(result.message, result.success)
            assertTrue(AdrenotoolsManager(context).enumarateInstalledDrivers().contains(entry.id))
            assertTrue(File(context.filesDir, "contents/adrenotools/${entry.id}/libvulkan.so").isFile)
        }
    }

    @Test
    fun manifestContentIdMatchesInstalledProfileVersionAndRevision() = runBlocking {
        val profile = """
            {"type":"Box64","versionName":"box64-fixture","versionCode":7,"description":"Fixture","files":[]}
        """.trimIndent().toByteArray()
        val archive = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(archive)).use { tar ->
            tar.writeFile("profile.json", profile)
            tar.writeFile("payload", "component payload".toByteArray())
        }

        MockWebServer().use { server ->
            val entry = server.manifestEntry("box64-fixture-7", "box64.wcp", archive.toByteArray())
            val type = ContentType.CONTENT_TYPE_BOX64
            val result = ManifestInstaller.downloadAndInstallContent(context, entry, type)

            assertTrue(result.message, result.success)
            val manager = ContentsManager(context)
            manager.syncContents()
            val installed = manager.getProfiles(type).orEmpty().single { it.verName == "box64-fixture" }
            assertEquals(entry.id, "${installed.verName}-${installed.verCode}")
            assertEquals("component payload", ContentsManager.getSourceFile(context, installed, "payload").readText())
        }
    }

    @Test
    fun manifestRawWrapperIsCachedUnderTheReleaseFileName() = runBlocking {
        val archive = ByteArrayOutputStream()
        TarArchiveOutputStream(ZstdCompressorOutputStream(archive)).use { tar ->
            tar.writeFile("d3d9.dll", "wrapper payload".toByteArray())
        }
        val artifact = archive.toByteArray()

        MockWebServer().use { server ->
            val entry = server.manifestEntry("dxvk-fixture", "dxvk-fixture.tzst", artifact)
            val result = ManifestInstaller.downloadAndInstallContent(context, entry, ContentType.CONTENT_TYPE_DXVK)

            assertTrue(result.message, result.success)
            assertArrayEquals(artifact, File(context.filesDir, "assets/dxwrapper/dxvk-fixture.tzst").readBytes())
        }
    }

    private fun MockWebServer.manifestEntry(id: String, filename: String, archive: ByteArray): ManifestEntry {
        start()
        enqueue(MockResponse().setBody(Buffer().write(archive)))
        return ManifestEntry(
            id = id,
            name = "Test component",
            url = url("/$filename").toString(),
            sizeBytes = archive.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) },
        )
    }

    private fun TarArchiveOutputStream.writeFile(name: String, data: ByteArray) {
        putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
        write(data)
        closeArchiveEntry()
    }
}
