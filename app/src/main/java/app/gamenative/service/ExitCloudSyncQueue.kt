package app.gamenative.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Keeps a post-game sync alive across navigation and lets a relaunch wait for it. */
internal class ExitCloudSyncQueue(private val scope: CoroutineScope) {
    private val jobs = mutableMapOf<String, Job>()

    fun start(appId: String, sync: suspend () -> Unit): Job = synchronized(jobs) {
        jobs[appId]?.takeUnless { it.isCompleted }?.let { return@synchronized it }
        val job = scope.launch(start = CoroutineStart.LAZY) { sync() }
        jobs[appId] = job
        job.invokeOnCompletion {
            synchronized(jobs) {
                if (jobs[appId] === job) jobs.remove(appId)
            }
        }
        job.start()
        job
    }

    suspend fun await(appId: String) {
        synchronized(jobs) { jobs[appId] }?.join()
    }

    companion object {
        val process = ExitCloudSyncQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }
}
