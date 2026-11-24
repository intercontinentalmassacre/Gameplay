package app.gamenative.diagnostics

import android.content.Context
import app.gamenative.CrashContext
import app.gamenative.CrashContextSnapshot
import app.gamenative.utils.DiagnosticsLog
import com.winlator.core.envvars.EnvVars
import com.winlator.container.Container
import com.winlator.xenvironment.components.EnvRedactor
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Collects diagnostics for the game selected in the library. */
object SupportBundle {

    data class BundleContents(
        val summary: String,
        val launchReport: org.json.JSONObject,
        val eventsJsonl: String,
        val redactedEnv: String,
        val containerConfig: String,
        val wineLog: String?,
        val wrapperLog: String?,
    )

    /** Gathers the bundle contents in memory so the UI can show them before export. */
    fun collect(
        context: Context,
        container: Container?,
        appId: String,
        gameName: String,
    ): BundleContents {
        val snapshot = CrashContext.snapshot().takeIf { it.appId == appId }
            ?: CrashContextSnapshot(appId = appId, gameName = gameName, containerId = container?.id?.toString())
        val trace = LaunchTrace.snapshot(appId)
        val sessionId = trace.sessionId
        val report = LaunchReport.build(context, container, snapshot, sessionId, trace.events)

        val summary = buildString {
            appendLine("Gameplay diagnostics bundle")
            appendLine("Generated: ${java.time.Instant.now()}")
            appendLine("Session: ${sessionId ?: "unknown"}")
            appendLine()
            appendLine("Game: ${snapshot.gameName ?: "unknown"} (appId ${snapshot.appId ?: "unknown"})")
            appendLine("Launch stage: ${snapshot.launchStage}")
            appendLine("Container: ${snapshot.containerId ?: "unknown"}")
            appendLine("Runtime: ${snapshot.runtime ?: "unknown"}")
            appendLine("GPU: ${snapshot.gpu ?: "unknown"}")
            appendLine()
            appendLine("Do not paste it into public channels before reviewing it.")
        }

        val containerConfig = container?.let {
            val json = it.getContainerJson()
            if (json.isNullOrBlank()) "{}" else EnvRedactor.redactText(json)
        } ?: "{}"

        val redactedEnv = container?.let { EnvRedactor.redact(EnvVars(it.envVars)) } ?: "[]"

        val events = LaunchTrace.toJsonLines(trace.events, sessionId)

        return BundleContents(
            summary = summary,
            launchReport = report,
            eventsJsonl = events,
            redactedEnv = redactedEnv,
            containerConfig = containerConfig,
            wineLog = readLog(DiagnosticsLog.wineFile(context, appId)),
            wrapperLog = readLog(DiagnosticsLog.file(context, appId)),
        )
    }

    /** Writes the bundle to [destZip]. Call on a background thread. */
    fun export(
        contents: BundleContents,
        destZip: File,
    ): File {
        val destination = destZip.toPath().toAbsolutePath()
        Files.createDirectories(destination.parent)
        val staged = Files.createTempFile(destination.parent, ".support-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(staged)).use { zos ->
                writeEntry(zos, "summary.txt", contents.summary)
                writeEntry(zos, "launch-report.json", contents.launchReport.toString(2))
                writeEntry(zos, "events.jsonl", contents.eventsJsonl)
                writeEntry(zos, "redacted-env.txt", contents.redactedEnv)
                writeEntry(zos, "container-config.json", contents.containerConfig)
                contents.wineLog?.let { writeEntry(zos, "wine.log", it) }
                contents.wrapperLog?.let { writeEntry(zos, "wrapper.log", it) }
            }
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staged)
        }
        return destZip
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun readLog(file: File): String? = try {
        java.io.RandomAccessFile(file, "r").use { log ->
            val bytes = ByteArray(minOf(log.length(), 200_000L).toInt())
            log.readFully(bytes)
            EnvRedactor.redactText(bytes.toString(Charsets.UTF_8))
        }
    } catch (_: java.io.IOException) {
        null
    }
}
