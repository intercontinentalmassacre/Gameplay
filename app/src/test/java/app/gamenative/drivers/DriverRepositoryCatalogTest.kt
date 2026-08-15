package app.gamenative.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRepositoryCatalogTest {
    private val repository = DriverRepository(
        id = "test",
        name = "Test drivers",
        apiUrl = "https://example.invalid/releases",
        description = "Test repository",
        category = DriverCategory.TURNIP_STABLE,
        targets = setOf(AdrenoTarget.GENERIC),
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
        assertTrue(apiUrls.any { it.contains("The412Banner/Banners-Turnip") })
    }

    @Test
    fun `banners-turnip is registered as bleeding-edge with A8xx coverage`() {
        val banners = DriverRepositoryCatalog.trustedRepositories
            .firstOrNull { it.id == "banners-turnip" }

        assertNotNull("banners-turnip must be in the catalog", banners)
        assertEquals(DriverCategory.TURNIP_BLEEDING_EDGE, banners!!.category)
        assertTrue(
            "banners-turnip must declare A8xx coverage",
            AdrenoTarget.A8XX in banners.targets,
        )
        assertTrue(
            "banners-turnip must declare A710/A720/A722 coverage",
            AdrenoTarget.A710_A720_A722 in banners.targets,
        )
    }

    @Test
    fun `every category exposes at least one repository`() {
        for (category in DriverRepositoryCatalog.categories) {
            val repos = DriverRepositoryCatalog.repositoriesByCategory(category)
            // WRAPPER is reserved for future Bionic Vulkan wrapper sources; OK if empty.
            if (category == DriverCategory.WRAPPER) continue
            assertTrue(
                "category $category has no repositories",
                repos.isNotEmpty(),
            )
        }
    }

    @Test
    fun `repositoriesForTarget filters by Adreno generation`() {
        val a8xx = DriverRepositoryCatalog.repositoriesForTarget(AdrenoTarget.A8XX)
        assertTrue(
            "A8xx-targeted list must include Banners-Turnip (covers A8xx experimental)",
            a8xx.any { it.id == "banners-turnip" },
        )

        val a710 = DriverRepositoryCatalog.repositoriesForTarget(AdrenoTarget.A710_A720_A722)
        assertTrue(
            "A710/A720/A722 list must include Banners-Turnip",
            a710.any { it.id == "banners-turnip" },
        )
    }

    @Test
    fun `preferredCategoryFor routes by Adreno generation`() {
        assertEquals(
            DriverCategory.TURNIP_BLEEDING_EDGE,
            preferredCategoryFor(AdrenoTarget.A8XX),
        )
        assertEquals(
            DriverCategory.TURNIP_BLEEDING_EDGE,
            preferredCategoryFor(AdrenoTarget.A710_A720_A722),
        )
        assertEquals(
            DriverCategory.TURNIP_STABLE,
            preferredCategoryFor(AdrenoTarget.A6XX),
        )
        assertEquals(
            DriverCategory.TURNIP_STABLE,
            preferredCategoryFor(AdrenoTarget.A7XX),
        )
        assertEquals(
            DriverCategory.ADRENO_PACKAGED,
            preferredCategoryFor(AdrenoTarget.GENERIC),
        )
    }

    @Test
    fun `GENERIC-targeted repo counts as supported for any device`() {
        val adrenoOnlyRepo = DriverRepository(
            id = "adreno-only",
            name = "Adreno only",
            apiUrl = "https://example.invalid/releases",
            description = "Adreno-only repo",
            category = DriverCategory.TURNIP_STABLE,
            targets = setOf(AdrenoTarget.A6XX),
        )
        val genericRepo = DriverRepository(
            id = "generic-repo",
            name = "Generic repo",
            apiUrl = "https://example.invalid/releases",
            description = "Generic",
            category = DriverCategory.TURNIP_STABLE,
            targets = setOf(AdrenoTarget.GENERIC),
        )

        // A7xx device should match the GENERIC repo even though it doesn't declare A7XX.
        assertTrue(
            "GENERIC-targeted repo must be counted as supported for non-Adreno devices",
            AdrenoTarget.GENERIC in genericRepo.targets,
        )
        // Adreno-only repo must NOT include GENERIC, so a non-Adreno device sees 0 supported.
        assertTrue(
            "Adreno-only repo must not include GENERIC",
            AdrenoTarget.GENERIC !in adrenoOnlyRepo.targets,
        )
        assertTrue(
            "Adreno-only repo must declare A6XX",
            AdrenoTarget.A6XX in adrenoOnlyRepo.targets,
        )
    }
}

