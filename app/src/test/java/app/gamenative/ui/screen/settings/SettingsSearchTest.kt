package app.gamenative.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun `matches titles and keywords case insensitively`() {
        val entries = listOf(
            SettingsSearchEntry(SettingsCategory.RUNTIME, 1, listOf("wine", "proton")),
            SettingsSearchEntry(SettingsCategory.INTERFACE, 2, listOf("appearance")),
        )
        val titles = mapOf(1 to "Runtime", 2 to "Interface")

        assertEquals(
            listOf(entries[0]),
            filterSettings(entries, "PROTON") { titles.getValue(it) },
        )
        assertEquals(
            listOf(entries[1]),
            filterSettings(entries, "interface") { titles.getValue(it) },
        )
    }
}
