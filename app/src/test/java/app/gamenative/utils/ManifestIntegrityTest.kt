package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline integrity checks for the component manifest: both shipped copies must stay in
 * sync, ids must be unique per type, and entry metadata must be well formed. Catches the
 * failure modes that break installs silently (id mismatch, stale assets copy, bad urls).
 */
class ManifestIntegrityTest {

    private fun locateRepoFile(relative: String): File {
        val workingDir = File(System.getProperty("user.dir"))
        val direct = File(workingDir, relative)
        if (direct.exists()) return direct
        val parent = workingDir.parentFile
        if (parent != null) {
            val parentFile = File(parent, relative)
            if (parentFile.exists()) return parentFile
        }
        throw IllegalStateException("$relative not found from ${workingDir.absolutePath}")
    }

    private fun loadManifest(): ManifestData {
        val parsed = ManifestRepository.parseManifest(locateRepoFile("manifest.json").readText())
        assertNotNull("Failed to parse manifest.json", parsed)
        return parsed!!
    }

    @Test
    fun assetsCopyMatchesRootCopy() {
        val root = locateRepoFile("manifest.json").readBytes()
        val assets = locateRepoFile("app/src/main/assets/manifest.json").readBytes()
        assertTrue(
            "assets/manifest.json is out of sync with the root manifest.json",
            root.contentEquals(assets),
        )
    }

    @Test
    fun parsesAndHasEntries() {
        val manifest = loadManifest()
        assertTrue("Manifest has no items", manifest.items.values.sumOf { it.size } > 0)
    }

    @Test
    fun idsAreUniquePerType() {
        val manifest = loadManifest()
        for ((type, entries) in manifest.items) {
            val ids = entries.map { it.id }
            val duplicates = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            assertTrue("Duplicate ids in $type: $duplicates", duplicates.isEmpty())
        }
    }

    @Test
    fun urlsAreHttps() {
        val manifest = loadManifest()
        for ((type, entries) in manifest.items) {
            for (entry in entries) {
                assertTrue(
                    "Non-https url in $type/${entry.id}: ${entry.url}",
                    entry.url.startsWith("https://"),
                )
            }
        }
    }

    @Test
    fun driverChecksumsAreWellFormed() {
        val manifest = loadManifest()
        val drivers = manifest.items[ManifestContentTypes.DRIVER].orEmpty()
        assertTrue("No driver entries", drivers.isNotEmpty())
        val shaPattern = Regex("[0-9a-f]{64}")
        for (entry in drivers) {
            entry.sha256?.let { sha ->
                assertTrue("Bad sha256 for driver ${entry.id}: $sha", shaPattern.matches(sha))
            }
            entry.sizeBytes?.let { size ->
                assertTrue("Bad sizeBytes for driver ${entry.id}: $size", size > 0)
            }
        }
    }
}
