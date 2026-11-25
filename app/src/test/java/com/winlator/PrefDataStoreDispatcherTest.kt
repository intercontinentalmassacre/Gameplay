package com.winlator

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Guards the IO-pool starvation deadlock from upstream GameNative #1956:
// getString/getBoolean use `runBlocking { dataStore.data.first() }`, so if DataStore's
// own scope also ran on Dispatchers.IO, enough concurrent readers park every worker in
// the pool and none of them can ever be served. DataStore therefore owns a private
// single thread, which stays runnable no matter how full Dispatchers.IO is.
@RunWith(RobolectricTestRunner::class)
class PrefDataStoreDispatcherTest {

    // Dispatchers.IO's default parallelism is max(64, ncores) -- saturate all of it.
    private val ioPoolSize = maxOf(64, Runtime.getRuntime().availableProcessors())

    @Before
    fun setUp() {
        PrefManager.deInit()
        PrefManager.init(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() {
        PrefManager.deInit()
    }

    @Test
    fun `both pref managers give their datastore a private named thread`() {
        val winlatorDispatcher = dataStoreDispatcherOf(PrefManager::class.java, PrefManager)
        assertEquals("WinlatorPrefDataStore", threadNameOf(winlatorDispatcher).substringBefore(" @coroutine#"))

        val pluviaDispatcher = dataStoreDispatcherOf(
            app.gamenative.PrefManager::class.java,
            app.gamenative.PrefManager,
        )
        assertEquals("PrefDataStore", threadNameOf(pluviaDispatcher).substringBefore(" @coroutine#"))
    }

    // a blocking read whose very first collection has to perform the DataStore read still
    // completes when every IO worker is parked. against a Dispatchers.IO-backed store this
    // hangs until the timeout.
    @Test(timeout = 60_000)
    fun `blocking pref read completes while every IO worker is parked`() {
        // no prior write: getString's first() is what triggers the initial DataStore read
        val release = parkEveryIoWorker()
        try {
            assertEquals("missing", PrefManager.getString("starvation_probe", "missing"))
        } finally {
            release.countDown()
        }
    }

    // the mechanism itself: DataStore served by a private single thread reads while the
    // IO pool is fully occupied.
    @Test(timeout = 60_000)
    fun `datastore on a private dispatcher reads while every IO worker is parked`() {
        val file = File.createTempFile("prefs-starvation", ".preferences_pb").apply {
            delete()
            deleteOnExit()
        }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "TestPrefDataStore") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher + SupervisorJob()),
            ) { file }

            val release = parkEveryIoWorker()
            try {
                val preferences = runBlocking { store.data.first() }
                assertTrue(preferences.asMap().isEmpty())
            } finally {
                release.countDown()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun dataStoreDispatcherOf(type: Class<*>, instance: Any): CoroutineDispatcher {
        val field = type.getDeclaredField("dataStoreDispatcher")
        field.isAccessible = true
        val dispatcher = field.get(instance) as CoroutineDispatcher

        // the deadlock returns the moment this is Dispatchers.IO again
        assertTrue("datastore must not share the IO pool", dispatcher !== Dispatchers.IO)
        return dispatcher
    }

    // kotlinx's debug agent appends " @coroutine#n" to the thread name under test
    private fun threadNameOf(dispatcher: CoroutineDispatcher): String =
        runBlocking(dispatcher) { Thread.currentThread().name }

    private fun parkEveryIoWorker(): CountDownLatch {
        val parked = CountDownLatch(ioPoolSize)
        val release = CountDownLatch(1)
        val hogs = CoroutineScope(Dispatchers.IO + SupervisorJob())
        repeat(ioPoolSize) {
            hogs.launch {
                parked.countDown()
                release.await()
            }
        }

        val saturated = parked.await(30, TimeUnit.SECONDS)
        if (!saturated) release.countDown()
        assertTrue("IO pool never saturated", saturated)
        return release
    }
}
