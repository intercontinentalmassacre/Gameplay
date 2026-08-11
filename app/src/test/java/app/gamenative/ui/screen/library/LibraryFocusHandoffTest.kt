package app.gamenative.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFocusHandoffTest {

    @Test
    fun restoresFocusOnlyWhenInteractiveCompanionDetachesToLibrary() {
        assertTrue(
            shouldRestoreFocusAfterCompanionDetach(
                wasCompanionAttached = true,
                isCompanionAttached = false,
                hasSelectedItem = false,
                hasBlockingOverlay = false,
            ),
        )

        assertFalse(shouldRestoreFocusAfterCompanionDetach(false, false, false, false))
        assertFalse(shouldRestoreFocusAfterCompanionDetach(true, true, false, false))
        assertFalse(shouldRestoreFocusAfterCompanionDetach(true, false, true, false))
        assertFalse(shouldRestoreFocusAfterCompanionDetach(true, false, false, true))
    }
}
