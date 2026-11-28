package app.gamenative.utils

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import app.gamenative.ui.screen.library.formatLibraryPlayDuration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LibrarySessionTrackerTest {
    @Test
    fun `a short session counts active time once and excludes background time`() {
        val timer = PlaySessionTimer()
        timer.resume(10_000)
        timer.resume(15_000)
        timer.pause(25_000)
        timer.pause(120_000)
        timer.resume(120_000)
        timer.pause(125_000)

        assertEquals(20_000L, timer.durationMs)
    }

    @Test
    fun `duration formatting distinguishes sub-minute sessions and hour boundaries`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.US) }
        val context = app.createConfigurationContext(config)

        assertEquals("Under 1 min", formatLibraryPlayDuration(context, 59_999))
        assertEquals("1 min", formatLibraryPlayDuration(context, 60_000))
        assertEquals("1 hr", formatLibraryPlayDuration(context, 3_600_000))
        assertEquals("1 hr, 1 min", formatLibraryPlayDuration(context, 3_660_000))
    }
}
