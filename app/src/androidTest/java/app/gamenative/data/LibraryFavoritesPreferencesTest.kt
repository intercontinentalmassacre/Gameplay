package app.gamenative.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryFavoritesPreferencesTest {
    @Test
    fun reloadPreservesReplacementAndClearsAnUnfavoritedPin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reloadContext = context.createDeviceProtectedStorageContext()
        val name = "library_favorites_test_${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            assertEquals(LibraryFavorites(), preferences.readLibraryFavorites())
            val steam = "STEAM_42"
            val gog = "GOG_42"
            val favorites = LibraryFavorites(setOf(steam, gog), steam)
            preferences.writeLibraryFavorites(favorites)
            preferences.writeLibraryFavorites(favorites.togglePlayNext(gog))
            assertTrue(preferences.edit().commit())

            // Read the saved file through fresh preferences in a separate storage directory.
            assertTrue(reloadContext.moveSharedPreferencesFrom(context, name))
            val reopened = reloadContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertEquals(LibraryFavorites(setOf(steam, gog), gog), reopened.readLibraryFavorites())

            reopened.writeLibraryFavorites(reopened.readLibraryFavorites().toggleFavorite(gog))
            assertTrue(reopened.edit().commit())
            assertTrue(context.moveSharedPreferencesFrom(reloadContext, name))
            assertEquals(
                LibraryFavorites(setOf(steam)),
                context.getSharedPreferences(name, Context.MODE_PRIVATE).readLibraryFavorites(),
            )
        } finally {
            context.deleteSharedPreferences(name)
            reloadContext.deleteSharedPreferences(name)
        }
    }
}
