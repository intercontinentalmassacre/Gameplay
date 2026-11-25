package app.gamenative.utils

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Opt in on an isolated Android test device with -e liveManifest true.
@LargeTest
@RunWith(AndroidJUnit4::class)
class LiveManifestIdCorrelationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manifestIdsMatchInstalledIds() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveManifest") == "true")
        val manifest = checkNotNull(
            ManifestRepository.parseManifest(context.assets.open("manifest.json").bufferedReader().use { it.readText() }),
        )
        val items = manifest.items.filterKeys { it != ManifestContentTypes.PROTON }

        for ((typeKey, entries) in items) {
            println("Checking manifest type=$typeKey entries=${entries.size}")
            for (entry in entries) {
                if (typeKey == ManifestContentTypes.DRIVER) {
                    verifyDriverEntry(entry)
                } else if (entry.archiveFormat == "tzst" || entry.url.endsWith(".tzst")) {
                    verifyTzstEntry(typeKey, entry)
                } else {
                    verifyContentEntry(typeKey, entry)
                }
            }
        }
    }

    private suspend fun verifyDriverEntry(entry: ManifestEntry) {
        val manager = AdrenotoolsManager(context)
        val driverRoot = File(context.filesDir, "contents/adrenotools")
        var installedId: String? = null
        try {
            println("Driver download/install id=${entry.id} name=${entry.name} url=${entry.url}")
            val result = ManifestInstaller.downloadAndInstallDriver(context, entry)
            println("Driver install result id=${entry.id} success=${result.success} message=${result.message}")
            assertTrue(
                "Driver install failed for ${entry.id}: ${result.message}",
                result.success,
            )
            val installedDirs = driverRoot.listFiles()
                ?.filter { it.isDirectory && File(it, "meta.json").exists() }
                ?.map { it.name }
                .orEmpty()
            println("Driver installed dirs: $installedDirs")
            installedId = installedDirs.firstOrNull { it.equals(entry.id, ignoreCase = true) }
            println("Driver match id=${entry.id} matched=${installedId != null}")
            assertTrue(
                "Driver ID mismatch for ${entry.id}. Installed: $installedDirs",
                installedId != null,
            )
        } finally {
            if (installedId != null) {
                println("Driver cleanup id=$installedId")
                manager.removeDriver(installedId)
            }
        }
    }

    private suspend fun verifyContentEntry(typeKey: String, entry: ManifestEntry) {
        val expectedType = contentTypeForKey(typeKey)
        val manager = ContentsManager(context)
        var installedProfile: ContentProfile? = null
        try {
            println("Content download/install type=$typeKey id=${entry.id} name=${entry.name} url=${entry.url}")
            val result = ManifestInstaller.downloadAndInstallContent(context, entry, expectedType)
            println("Content install result type=$typeKey id=${entry.id} success=${result.success} message=${result.message}")
            manager.syncContents()
            val profiles = manager.getProfiles(expectedType).orEmpty()
            println("Content installed profiles type=$typeKey count=${profiles.size}")
            installedProfile = profiles.firstOrNull { profile ->
                val installedId = "${profile.verName}-${profile.verCode}"
                installedId.equals(entry.id, ignoreCase = true)
            }
            if (!result.success && installedProfile == null) {
                assertTrue(
                    "Content install failed for ${entry.id} (${typeKey}): ${result.message}",
                    result.success,
                )
            }
            val installedIds = profiles.map { "${it.verName}-${it.verCode}" }
            println("Content installed IDs type=$typeKey ids=$installedIds")
            println("Content match type=$typeKey id=${entry.id} matched=${installedProfile != null}")
            assertTrue(
                "Content ID mismatch for ${entry.id} (${typeKey}). Installed: $installedIds",
                installedProfile != null,
            )
        } finally {
            if (installedProfile != null) {
                println("Content cleanup type=$typeKey verName=${installedProfile.verName} verCode=${installedProfile.verCode}")
                manager.removeContent(installedProfile)
            }
        }
    }

    private suspend fun verifyTzstEntry(typeKey: String, entry: ManifestEntry) {
        val expectedType = contentTypeForKey(typeKey)
        val cacheFile = File(File(context.filesDir, "assets/dxwrapper"), entry.url.substringAfterLast("/"))
        try {
            println("Tzst download/install type=$typeKey id=${entry.id} name=${entry.name} url=${entry.url}")
            val result = ManifestInstaller.downloadAndInstallContent(context, entry, expectedType)
            println("Tzst install result type=$typeKey id=${entry.id} success=${result.success} message=${result.message}")
            assertTrue(
                "Tzst install failed for ${entry.id} ($typeKey): ${result.message}",
                result.success,
            )
            assertTrue(
                "Tzst not cached for ${entry.id} ($typeKey) at ${cacheFile.absolutePath}",
                cacheFile.exists() && cacheFile.length() > 0,
            )
        } finally {
            cacheFile.delete()
        }
    }

    private fun contentTypeForKey(key: String): ContentProfile.ContentType {
        return when (key.lowercase()) {
            ManifestContentTypes.DXVK -> ContentProfile.ContentType.CONTENT_TYPE_DXVK
            ManifestContentTypes.VKD3D -> ContentProfile.ContentType.CONTENT_TYPE_VKD3D
            ManifestContentTypes.BOX64 -> ContentProfile.ContentType.CONTENT_TYPE_BOX64
            ManifestContentTypes.WOWBOX64 -> ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
            ManifestContentTypes.FEXCORE -> ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
            ManifestContentTypes.WINE -> ContentProfile.ContentType.CONTENT_TYPE_WINE
            else -> throw IllegalArgumentException("Unsupported manifest type: $key")
        }
    }
}
