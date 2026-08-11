package app.gamenative.ui.screen.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryFocusTargetTest {
    @Test
    fun `empty library focuses root and populated library focuses content`() {
        assertEquals(LibraryFocusTarget.ROOT, libraryFocusTarget(0))
        assertEquals(LibraryFocusTarget.CONTENT, libraryFocusTarget(1))
    }
}
