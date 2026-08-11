package app.gamenative.diagnostics

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONObject

/**
 * In-memory + on-disk record of launch stage transitions. Every transition is
 * appended to `events.jsonl` for the active session, so even a mid-launch kill
 * leaves a durable trace. No paths, credentials or tokens are ever stored.
 */
object LaunchTrace {

    data class StageEvent(
        val timeMs: Long,
        val stage: LaunchStage,
        val durationMs: Long,
        val result: String,
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var startedAtMs: Long = 0L

    @Volatile
    private var lastStageAtMs: Long = 0L

    private val events = mutableListOf<StageEvent>()
    private val lock = ReentrantLock()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.getExternalFilesDir(null), "launch_trace").apply { if (!exists()) mkdirs() }
    }

    fun session(): String? = sessionId

    /** Begins a trace for a fresh [sessionId]. */
    fun begin(sessionId: String = UUID.randomUUID().toString()) {
        lock.withLock {
            this.sessionId = sessionId
            val now = System.currentTimeMillis()
            startedAtMs = now
            lastStageAtMs = now
            events.clear()
            appendEventLocked(LaunchStage.REQUEST_CREATED, "created")
            persistLocked()
        }
    }

    /** Records a stage transition. [result] is typically "success"/"running". */
    fun stage(stage: LaunchStage, result: String = "running") {
        lock.withLock {
            if (sessionId == null) return
            appendEventLocked(stage, result)
            persistLocked()
        }
    }

    /** Maps a legacy string stage (from older CrashContext) to the enum. */
    fun stageLegacy(label: String, result: String = "running") {
        LaunchStage.fromLegacy(label)?.let { stage(it, result) }
    }

    /** Marks the trace as finished (EXITED/FAILED/CANCELLED) and clears the session. */
    fun finish(result: String) {
        lock.withLock {
            if (sessionId == null) return
            val terminal = when (result) {
                "cancelled" -> LaunchStage.CANCELLED
                "failed" -> LaunchStage.FAILED
                else -> LaunchStage.EXITED
            }
            appendEventLocked(terminal, result)
            persistLocked()
            sessionId = null
            events.clear()
            startedAtMs = 0L
            lastStageAtMs = 0L
        }
    }

    fun eventsSnapshot(): List<StageEvent> = lock.withLock { events.toList() }

    fun toJsonLines(events: List<StageEvent> = eventsSnapshot()): String {
        val session = sessionId
        return events.joinToString("\n") { e ->
            JSONObject()
                .put("time", java.time.Instant.ofEpochMilli(e.timeMs).toString())
                .put("sessionId", session)
                .put("stage", e.stage.name)
                .put("durationMs", e.durationMs)
                .put("result", e.result)
                .toString()
        }
    }

    private fun appendEventLocked(stage: LaunchStage, result: String) {
        val now = System.currentTimeMillis()
        val durationMs = if (lastStageAtMs == 0L) 0L else now - lastStageAtMs
        events.add(StageEvent(timeMs = now, stage = stage, durationMs = durationMs, result = result))
        lastStageAtMs = now
    }

    private fun persistLocked() {
        val dir = dir() ?: return
        try {
            val file = File(dir, "${sessionId ?: "session"}.events.jsonl")
            file.writeText(toJsonLines(events))
        } catch (_: Exception) {
        }
    }
}
