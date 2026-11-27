package app.gamenative.utils

import android.content.Context
import android.os.SystemClock
import app.gamenative.db.dao.LibraryPlayHistoryDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class LibrarySessionTracker @Inject constructor(
    private val history: LibraryPlayHistoryDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun observe(appId: String) = history.observe(appId)

    fun recordPlayed(appId: String) = write {
        history.recordStart(appId, System.currentTimeMillis())
    }

    fun start(appId: String): Session {
        val startedAt = System.currentTimeMillis()
        val session = Session(appId, startedAt)
        write { history.recordStart(appId, startedAt) }
        return session
    }

    inner class Session internal constructor(val appId: String, private val startedAt: Long) {
        private val timer = PlaySessionTimer().apply { resume(SystemClock.uptimeMillis()) }
        private var finished = false

        fun resume() {
            if (!finished) timer.resume(SystemClock.uptimeMillis())
        }

        fun pause() = timer.pause(SystemClock.uptimeMillis())

        fun finish() {
            if (finished) return
            finished = true
            pause()
            val durationMs = timer.durationMs
            if (durationMs > 0) write { history.recordCompletion(appId, startedAt, durationMs) }
        }
    }

    private fun write(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Could not save library play history")
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun librarySessions(): LibrarySessionTracker
    }

    companion object {
        fun from(context: Context): LibrarySessionTracker =
            EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java).librarySessions()
    }
}

internal class PlaySessionTimer {
    private var resumedAt: Long? = null
    var durationMs: Long = 0
        private set

    fun resume(now: Long) {
        if (resumedAt == null) resumedAt = now
    }

    fun pause(now: Long) {
        val start = resumedAt ?: return
        resumedAt = null
        durationMs += (now - start).coerceAtLeast(0)
    }
}
