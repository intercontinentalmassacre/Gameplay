package app.gamenative.utils

import android.content.Context
import app.gamenative.R
import com.winlator.container.Container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import timber.log.Timber

/** Uses the same component cache at install and launch. Prefix setup still happens at launch. */
internal object ContainerComponentPreparation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installLock = Mutex()

    fun prefetchForInstall(context: Context, appId: String, savedConfig: String?) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                val config = savedConfig ?: run {
                    val container = ContainerUtils.getOrCreateContainer(appContext, appId, installWinePrefix = false)
                    if (container.platform.equals(Container.PLATFORM_ANDROID, ignoreCase = true)) return@launch
                    container.containerJson
                }
                ensureInstalled(appContext, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The game download is independent; launch retries any missing components.
                Timber.w(e, "Could not prefetch container components for %s", appId)
            }
        }
    }

    suspend fun ensureInstalled(
        context: Context,
        config: String,
        onMessage: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ) = installLock.withLock {
        // Resolve after taking the lock so a launch does not download what prefetch just installed.
        val requests = BestConfigService.resolveMissingManifestInstallRequests(
            context, Json.parseToJsonElement(config).jsonObject, "exact_gpu_match",
        )
        for (request in requests) {
            onMessage(context.getString(R.string.main_downloading_entry, request.entry.name))
            val result = ManifestInstaller.installManifestEntry(
                context, request.entry, request.isDriver, request.contentType,
            ) { progress -> onProgress(progress.coerceIn(0f, 1f)) }
            check(result.success) { "${request.entry.name}: ${result.message}" }
        }
    }
}
