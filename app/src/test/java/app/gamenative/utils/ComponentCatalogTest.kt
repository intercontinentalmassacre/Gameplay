package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentCatalogTest {
    @Test
    fun `legacy manifest remains readable during migration`() {
        val parsed = ManifestRepository.parseManifest(
            """{"version":1,"updatedAt":"2026-06-30","items":{"dxvk":[{"id":"dxvk-2.7","name":"DXVK 2.7","url":"https://example.com/dxvk.wcp"}]}}""",
        )

        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertEquals("dxvk-2.7", parsed.items.getValue(ManifestContentTypes.DXVK).single().id)
    }

    @Test
    fun `v2 catalog is validated and adapted for existing consumers`() {
        val parsed = ManifestRepository.parseManifest(validCatalog())

        assertNotNull(parsed)
        assertEquals(2, parsed!!.schemaVersion)
        assertEquals("2026.08.1", parsed.catalogVersion)
        val entry = parsed.items.getValue(ManifestContentTypes.FEXCORE).single()
        assertEquals("fex-2607-arm64ec", entry.id)
        assertEquals("2607", entry.version)
        assertEquals(123456L, entry.sizeBytes)
        assertEquals(listOf(4096, 16384), entry.pageSizes)
        assertEquals("https://example.com/fex.wcp", entry.url)
    }

    @Test
    fun `v2 catalog rejects unverifiable artifacts`() {
        val invalid = validCatalog()
            .replace("\"sizeBytes\":123456", "\"sizeBytes\":0")
            .replace("\"sha256\":\"${"a".repeat(64)}\"", "\"sha256\":\"unknown\"")

        assertNull(ManifestRepository.parseManifest(invalid))
    }

    @Test
    fun `validator rejects duplicate ids unsafe paths and missing dependencies`() {
        val base = validEntry()
        val broken = ComponentCatalogDocument(
            schemaVersion = 2,
            catalogVersion = "2026.08.1",
            generatedAt = "2026-08-05T00:00:00Z",
            components = listOf(
                base.copy(requiredFiles = listOf("../escape"), requires = listOf("missing")),
                base,
            ),
        )

        val errors = ComponentCatalogValidator.validate(broken)
        assertTrue(errors.any { "duplicate component id" in it })
        assertTrue(errors.any { "unsafe path" in it })
        assertTrue(errors.any { "missing dependency" in it })
    }

    @Test
    fun `unsupported catalog schema is rejected`() {
        assertNull(ManifestRepository.parseManifest("""{"schemaVersion":99}"""))
    }

    private fun validCatalog(): String = """
        {
          "schemaVersion":2,
          "catalogVersion":"2026.08.1",
          "generatedAt":"2026-08-05T00:00:00Z",
          "components":[${validEntryJson()}]
        }
    """.trimIndent()

    private fun validEntryJson(): String = """
        {
          "id":"fex-2607-arm64ec",
          "type":"fexcore",
          "name":"FEX 2607 ARM64EC",
          "version":"2607",
          "channel":"experimental",
          "variant":"bionic",
          "abi":["arm64-v8a"],
          "archiveFormat":"wcp",
          "urls":["https://example.com/fex.wcp"],
          "sizeBytes":123456,
          "sha256":"${"a".repeat(64)}",
          "sourceRepository":"https://github.com/FEX-Emu/FEX",
          "sourceCommit":"${"b".repeat(40)}",
          "license":"MIT",
          "pageSizes":[4096,16384],
          "requiredFiles":["windows/system32/libwow64fex.dll"],
          "requires":[],
          "conflicts":["wowbox64-*"]
        }
    """.trimIndent()

    private fun validEntry() = ComponentCatalogEntry(
        id = "fex-2607-arm64ec",
        type = ManifestContentTypes.FEXCORE,
        name = "FEX 2607 ARM64EC",
        version = "2607",
        channel = "experimental",
        variant = "bionic",
        abi = listOf("arm64-v8a"),
        archiveFormat = "wcp",
        urls = listOf("https://example.com/fex.wcp"),
        sizeBytes = 123456,
        sha256 = "a".repeat(64),
        sourceRepository = "https://github.com/FEX-Emu/FEX",
        sourceCommit = "b".repeat(40),
        license = "MIT",
        pageSizes = listOf(4096, 16384),
        requiredFiles = listOf("windows/system32/libwow64fex.dll"),
        requires = emptyList(),
        conflicts = listOf("wowbox64-*"),
    )
}
