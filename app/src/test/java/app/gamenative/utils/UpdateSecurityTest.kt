package app.gamenative.utils

import app.gamenative.BuildConfig
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateSecurityTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `accepts only trusted GitHub asset URLs`() {
        assertTrue(UpdateChecker.isTrustedGitHubUrl("https://github.com/dontneedfriends-jpg/Gameplay/releases/download/v1.1.2/update.apk"))
        assertFalse(UpdateChecker.isTrustedGitHubUrl("https://example.com/update.apk"))
        assertFalse(UpdateChecker.isTrustedGitHubUrl("http://github.com/dontneedfriends-jpg/Gameplay/update.apk"))
    }

    @Test
    fun `rejects incompatible release metadata`() {
        val valid = UpdateChecker.ReleaseMetadata(
            packageName = BuildConfig.APPLICATION_ID,
            versionCode = BuildConfig.VERSION_CODE + 1,
            versionName = "1.1.2",
            assetName = "Gameplay-modern-release.apk",
            sha256 = "a".repeat(64),
            sizeBytes = 1L,
        )
        assertTrue(UpdateChecker.isCompatibleReleaseMetadata(valid))
        assertFalse(UpdateChecker.isCompatibleReleaseMetadata(valid.copy(versionCode = BuildConfig.VERSION_CODE)))
        assertFalse(UpdateChecker.isCompatibleReleaseMetadata(valid.copy(packageName = "app.gameplay.gold")))
        assertFalse(UpdateChecker.isCompatibleReleaseMetadata(valid.copy(sha256 = "invalid")))
    }

    @Test
    fun `computes APK checksum`() {
        val file = temporaryFolder.newFile("update.apk")
        file.writeBytes("Gameplay update".toByteArray(StandardCharsets.UTF_8))

        assertEquals(
            "8c898cccc6b3ecc358e88e4d8990fa950270ff97fd012b89b4527b67af29a2f9",
            UpdateInstaller.sha256(file),
        )
    }
}
