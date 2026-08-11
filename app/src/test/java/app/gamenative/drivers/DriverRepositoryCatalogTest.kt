package app.gamenative.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRepositoryCatalogTest {
    private val repository = DriverRepository(
        id = "test",
        name = "Test drivers",
        apiUrl = "https://example.invalid/releases",
        description = "Test repository",
    )

    @Test
    fun `parser keeps unique zip assets from published releases`() {
        val payload =
            """
            [
              {
                "name": "Release 1",
                "draft": false,
                "assets": [
                  {
                    "id": 10,
                    "name": "turnip.zip",
                    "browser_download_url": "https://example.invalid/turnip.zip",
                    "size": 2048
                  },
                  {
                    "id": 11,
                    "name": "sources.tar.gz",
                    "browser_download_url": "https://example.invalid/sources.tar.gz",
                    "size": 1024
                  }
                ]
              },
              {
                "tag_name": "Draft release",
                "draft": true,
                "assets": [
                  {
                    "id": 12,
                    "name": "draft.zip",
                    "browser_download_url": "https://example.invalid/draft.zip",
                    "size": 4096
                  }
                ]
              },
              {
                "tag_name": "Duplicate",
                "draft": false,
                "assets": [
                  {
                    "id": 13,
                    "name": "duplicate.zip",
                    "browser_download_url": "https://example.invalid/turnip.zip",
                    "size": 2048
                  }
                ]
              }
            ]
            """.trimIndent()

        val result = DriverRepositoryCatalog.parsePackages(repository, payload)

        assertEquals(1, result.size)
        assertEquals("test:10", result.single().id)
        assertEquals("Release 1 — turnip.zip", result.single().displayName)
        assertEquals(2048L, result.single().sizeBytes)
        assertEquals("Test drivers", result.single().repositoryName)
    }

    @Test
    fun `trusted repositories point to the Gameplay supported community sources`() {
        val apiUrls = DriverRepositoryCatalog.trustedRepositories.map(DriverRepository::apiUrl)

        assertTrue(apiUrls.any { it.contains("StevenMXZ/Adreno-Tools-Drivers") })
        assertTrue(apiUrls.any { it.contains("whitebelyash/AdrenoToolsDrivers") })
        assertTrue(apiUrls.any { it.contains("MrPurple666/purple-turnip") })
        assertTrue(apiUrls.any { it.contains("zoerakk/qualcomm-adreno-driver") })
    }
}
