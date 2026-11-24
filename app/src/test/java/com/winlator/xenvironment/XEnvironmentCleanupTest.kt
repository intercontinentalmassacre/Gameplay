package com.winlator.xenvironment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XEnvironmentCleanupTest {
    @Test
    fun attemptsEveryStopAndRetainsCleanupFailures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val environment = XEnvironment(context, ImageFs.find(context))
        val stopped = mutableListOf<String>()
        val first = UnsatisfiedLinkError("native cleanup unavailable")
        val second = UnsupportedOperationException("second cleanup error")
        fun component(name: String, failure: Throwable? = null) = object : EnvironmentComponent() {
            override fun start() = Unit
            override fun stop() {
                stopped += name
                if (failure != null) throw failure
            }
        }
        environment.addComponent(component("first", first))
        environment.addComponent(component("second", second))
        environment.addComponent(component("third"))

        val failure = assertThrows(UnsatisfiedLinkError::class.java) { environment.stopEnvironmentComponents() }

        assertSame(first, failure)
        assertEquals(listOf(second), failure.suppressed.toList())
        assertEquals(listOf("first", "second", "third"), stopped)
    }
}
