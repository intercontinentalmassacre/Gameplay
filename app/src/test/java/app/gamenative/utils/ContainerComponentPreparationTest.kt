package app.gamenative.utils

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test

class ContainerComponentPreparationTest {
    @Test
    fun secondContainerRechecksTheSharedCacheBeforeDownloading() = runBlocking {
        mockkObject(BestConfigService, ManifestInstaller)
        try {
            val context = mockk<Context>(relaxed = true)
            val entry = ManifestEntry("driver-1", "Driver", "https://example.invalid/driver.zip")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var installed = false
            coEvery { BestConfigService.resolveMissingManifestInstallRequests(context, any(), "exact_gpu_match") } coAnswers {
                if (installed) emptyList() else listOf(BestConfigService.ManifestInstallRequest(entry, isDriver = true))
            }
            coEvery { ManifestInstaller.installManifestEntry(context, entry, true, null, any()) } coAnswers {
                started.complete(Unit)
                finish.await()
                installed = true
                ManifestInstallResult(success = true, message = "Installed")
            }

            val first = async { ContainerComponentPreparation.ensureInstalled(context, """{"id":"STEAM_1"}""") }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                ContainerComponentPreparation.ensureInstalled(context, """{"id":"EPIC_2"}""")
            }
            assertFalse(second.isCompleted)
            finish.complete(Unit)
            first.await()
            second.await()
            coVerify(exactly = 1) { ManifestInstaller.installManifestEntry(context, entry, true, null, any()) }
        } finally {
            unmockkObject(BestConfigService, ManifestInstaller)
        }
    }
}
