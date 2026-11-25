package app.gamenative.diagnostics

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SupportBundleExportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `failed replacement preserves the last shareable archive`() {
        val dest = File(temp.root, "report.zip")
        val preview = SupportBundle.BundleContents("Preview", JSONObject(), "", "[]", "{}", null, null)
        SupportBundle.export(preview, dest)
        val original = dest.readBytes()

        val unreadableReport = object : JSONObject() {
            override fun toString(indentFactor: Int): String = throw IOException("Cannot serialize report")
        }
        assertThrows(IOException::class.java) {
            SupportBundle.export(preview.copy(launchReport = unreadableReport), dest)
        }
        assertArrayEquals(original, dest.readBytes())
        assertEquals(listOf("report.zip"), temp.root.list()?.toList())

        SupportBundle.export(preview.copy(summary = "Updated preview"), dest)
        ZipFile(dest).use { zip ->
            assertEquals("Updated preview", zip.getInputStream(zip.getEntry("summary.txt")).bufferedReader().use { it.readText() })
        }
    }
}
