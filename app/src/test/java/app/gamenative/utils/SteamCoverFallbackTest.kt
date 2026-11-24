package app.gamenative.utils

import app.gamenative.data.LibraryAssetsInfo
import app.gamenative.data.LibraryCapsuleInfo
import app.gamenative.data.LibraryHeroInfo
import app.gamenative.data.SteamApp
import app.gamenative.enums.Language
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamCoverFallbackTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun missingCapsuleMetadataFallsBackToKnownSteamFilenames() {
        assertEquals(
            listOf(
                "https://shared.steamstatic.com/store_item_assets/steam/apps/42/library_600x900_2x.jpg",
                "https://cdn.akamai.steamstatic.com/steam/apps/42/library_600x900_2x.jpg",
                "https://shared.steamstatic.com/store_item_assets/steam/apps/42/header.jpg",
                "https://cdn.akamai.steamstatic.com/steam/apps/42/header.jpg",
            ),
            SteamCoverUrls.resolve(SteamApp(id = 42))[CoverCache.Type.GRID_CAPSULE],
        )
    }

    @Test
    fun largeArtUsesRegularMetadataBeforeLocalizedHeader() {
        val app = SteamApp(
            id = 42,
            libraryAssets = LibraryAssetsInfo(
                libraryCapsule = LibraryCapsuleInfo(image = mapOf(Language.english to "published-capsule.jpg")),
                libraryHero = LibraryHeroInfo(image = mapOf(Language.english to "published-hero.jpg")),
            ),
            headerImage = mapOf(Language.english to "published-header.jpg"),
        )
        val urls = SteamCoverUrls.resolve(app)
        val modern = "https://shared.steamstatic.com/store_item_assets/steam/apps/42"
        val legacy = "https://cdn.akamai.steamstatic.com/steam/apps/42"

        assertEquals("$modern/published-capsule.jpg", urls.getValue(CoverCache.Type.GRID_CAPSULE).first())
        assertEquals(
            listOf(
                "$modern/published-hero.jpg", "$legacy/published-hero.jpg",
                "$modern/library_hero_2x.jpg", "$legacy/library_hero_2x.jpg",
                "$legacy/published-header.jpg", "$modern/header.jpg", "$legacy/header.jpg",
            ),
            urls.getValue(CoverCache.Type.GRID_HERO),
        )
    }

    @Test
    fun artwork404TriesNextUniqueCandidate() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("header art"))
            val primary = server.url("/capsule.jpg").toString()
            val urls = listOf(primary, primary, server.url("/header.jpg").toString())
            val dest = File(temporary.root, "cover.jpg")

            assertEquals(dest, CoverCache.Download().fetch(OkHttpClient(), urls, dest))
            assertEquals("header art", dest.readText())
            assertEquals("/capsule.jpg", server.takeRequest(1, SECONDS)?.path)
            assertEquals("/header.jpg", server.takeRequest(1, SECONDS)?.path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun cancellationStopsCurrentRequestAndSkipsFallback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val urls = listOf(server.url("/capsule.jpg").toString(), server.url("/header.jpg").toString())
            val dest = File(temporary.root, "cover.jpg")
            val executor = Executors.newSingleThreadExecutor()
            val download = CoverCache.Download()
            try {
                val result = executor.submit<File?> { download.fetch(OkHttpClient(), urls, dest) }
                assertEquals("/capsule.jpg", server.takeRequest(3, SECONDS)?.path)
                download.cancel()

                assertNull(result.get(3, SECONDS))
                assertFalse(dest.exists())
                assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
                assertEquals(1, server.requestCount)
            } finally {
                download.cancel()
                executor.shutdownNow()
            }
        }
    }
}
