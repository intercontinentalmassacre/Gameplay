package app.gamenative.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileProviderPathsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = "${context.packageName}.fileprovider"

    @Before
    fun attachProvider() {
        // Robolectric creates fresh directories per test; FileProvider caches roots by authority.
        val provider = requireNotNull(
            context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA),
        )
        FileProvider().attachInfo(context, provider)
    }

    @Test
    fun manifestConfiguresRestrictedSharingRoots() {
        val packageManager = context.packageManager
        val provider = requireNotNull(packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        assertFalse(provider.exported)
        val roots = mutableMapOf<String, Pair<String, String>>()
        requireNotNull(provider.loadXmlMetaData(packageManager, "android.support.FILE_PROVIDER_PATHS")).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name == "paths") continue
                assertFalse(parser.name == "root-path")
                roots[parser.getAttributeValue(null, "name")] =
                    parser.name to parser.getAttributeValue(null, "path")
            }
        }
        assertEquals("cache-path" to ".", roots["cache"])
        assertEquals("files-path" to "imagefs/usr/tmp/", roots["diagnostics"])
        assertEquals("external-files-path" to "crash_logs/", roots["crash_logs"])
        assertEquals("external-files-path" to "support_bundles/", roots["support_bundles"])
    }

    @Test
    fun resolvesUrisForFilesSharedByGameplay() {
        // AndroidX compares canonical paths using '/', including under Robolectric.
        assumeTrue(File.separatorChar == '/')
        val externalFiles = requireNotNull(context.getExternalFilesDir(null))
        val paths = mapOf(
            "cache" to File(context.cacheDir, "gameplay-update.apk"),
            "diagnostics" to DiagnosticsLog.file(context, "STEAM_123"),
            "crash_logs" to File(externalFiles, "crash_logs/report.txt"),
            "support_bundles" to File(externalFiles, "support_bundles/report.zip"),
        )

        for ((root, file) in paths) {
            val uri = FileProvider.getUriForFile(context, authority, file)
            assertEquals(root, uri.pathSegments.first())
            assertEquals(file.name, uri.lastPathSegment)
        }
    }

    @Test
    fun doesNotIssueUrisForUnrelatedPrivateFiles() {
        assumeTrue(File.separatorChar == '/')
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, authority, context.getDatabasePath("fixture.db"))
        }
    }
}
