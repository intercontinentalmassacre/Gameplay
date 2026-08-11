package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ManifestInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `download URLs preserve mirror order and remove duplicates`() {
        val entry = entry(
            url = "https://legacy.example/component.wcp",
            urls = listOf(
                "https://primary.example/component.wcp",
                "https://legacy.example/component.wcp",
            ),
        )

        assertEquals(
            listOf(
                "https://primary.example/component.wcp",
                "https://legacy.example/component.wcp",
            ),
            ManifestInstaller.downloadUrls(entry),
        )
    }

    @Test
    fun `verified artifact accepts exact size and SHA-256`() {
        val bytes = "verified component".toByteArray()
        val file = temporaryFolder.newFile("component.wcp").apply { writeBytes(bytes) }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

        ManifestInstaller.validateDownloadedArtifact(
            file,
            entry(sizeBytes = bytes.size.toLong(), sha256 = sha256),
        )
    }

    @Test
    fun `verified artifact rejects a changed payload`() {
        val file = temporaryFolder.newFile("component.wcp").apply { writeText("changed") }

        assertThrows(IllegalStateException::class.java) {
            ManifestInstaller.validateDownloadedArtifact(
                file,
                entry(sizeBytes = file.length(), sha256 = "a".repeat(64)),
            )
        }
    }

    private fun entry(
        url: String = "https://primary.example/component.wcp",
        urls: List<String> = emptyList(),
        sizeBytes: Long? = null,
        sha256: String? = null,
    ) = ManifestEntry(
        id = "component",
        name = "Component",
        url = url,
        urls = urls,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    )
}
