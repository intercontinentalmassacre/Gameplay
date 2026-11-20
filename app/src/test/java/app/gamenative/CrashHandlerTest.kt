package app.gamenative

import android.content.Context
import app.gamenative.diagnostics.LaunchTrace
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashHandlerTest {
    private var original: Thread.UncaughtExceptionHandler? = null
    private val context = mockk<Context>()
    private val failure = IllegalStateException("crash")
    private val crashThread = Thread("crashed worker")
    private val delivered = mutableListOf<Pair<Thread, Throwable>>()
    private val system = Thread.UncaughtExceptionHandler { thread, error -> delivered += thread to error }

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        every { context.applicationContext } returns context
        every { context.packageManager } throws IllegalStateException("report storage unavailable")
        mockkObject(PrefManager, LaunchTrace)
        every { PrefManager.recentlyCrashed = any() } returns Unit
        every { LaunchTrace.init(context) } returns Unit
        every { LaunchTrace.finish("failed") } returns Unit
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        unmockkObject(PrefManager, LaunchTrace)
    }

    @Test
    fun `reinitializing records the crash only once`() {
        Thread.setDefaultUncaughtExceptionHandler(system)
        repeat(3) { CrashHandler.initialize(context) }

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(crashThread, failure)

        verify(exactly = 1) { PrefManager.recentlyCrashed = true }
        verify(exactly = 1) { context.packageManager }
        assertEquals(listOf(crashThread to failure), delivered)
    }

    @Test
    fun `report failure still finishes the trace and preserves the original dispatch`() {
        CrashHandler(context, system).uncaughtException(crashThread, failure)

        verify(exactly = 1) { LaunchTrace.finish("failed") }
        assertEquals(listOf(crashThread to failure), delivered)
    }

    @Test
    fun `failure before report writing still reaches the default handler`() {
        every { PrefManager.recentlyCrashed = true } throws IllegalStateException("preferences unavailable")

        CrashHandler(context, system).uncaughtException(crashThread, failure)

        assertEquals(listOf(crashThread to failure), delivered)
    }
}
