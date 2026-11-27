package app.gamenative.utils

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.system.measureNanoTime

class ManifestCacheTest {
    @Test
    fun bundledCatalogIsReusedAcrossRemoteUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundled = checkNotNull(ManifestRepository.readLocalManifest(context))
        val remote = """{"version":1,"updatedAt":"2030-01-01","items":{}}"""
        val first = checkNotNull(ManifestRepository.parseManifest(remote))
        assertSame(first, ManifestRepository.parseManifest(remote.toCharArray().concatToString()))
        assertNull(ManifestRepository.parseManifest("{}"))
        assertNull(ManifestRepository.parseManifest(""))
        assertEquals("2030-01-02", ManifestRepository.parseManifest(remote.replace("01-01", "01-02"))?.updatedAt)
        assertSame(bundled, ManifestRepository.readLocalManifest(context))

        // Reproduce the previous bundled v1 read path on the same device and asset.
        val json = Json { ignoreUnknownKeys = true }
        fun readUncached(): ManifestData {
            val text = context.assets.open("manifest.json").bufferedReader().use { it.readText() }
            json.parseToJsonElement(text)
            return json.decodeFromString<ManifestData>(text)
        }
        assertEquals(bundled, readUncached())
        fun medianNanos(read: () -> Unit) = List(21) { measureNanoTime(read) }.sorted()[10]
        val uncached = medianNanos { readUncached() }
        val cached = medianNanos { ManifestRepository.readLocalManifest(context) }
        Log.i("GameplayCatalogTest", "bundled median ns: uncached=$uncached cached=$cached")
    }
}
