package app.gamenative.diagnostics

import android.content.Context
import app.gamenative.CrashContext
import app.gamenative.CrashHandler
import com.winlator.container.Container
import com.winlator.xenvironment.components.EnvRedactor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports a redacted support bundle (zip) for a launch: summary, launch-report,
 * events trace, redacted environment, container config, wine/backend logs and a
 * filtered logcat slice. Never writes secrets; env and command output is passed
 * through [EnvRedactor] before it touches disk.
 */
object SupportBundle {

    data class BundleContents(
        val summary: String,
        val launchReport: org.json.JSONObject,
        val eventsJsonl: String,
        val redactedEnv: String,
        val containerConfig: String,
        val wineLog: String?,
        val logcatFiltered: String,
    )

    /** Gathers the bundle contents in memory so the UI can show them before export. */
    fun collect(
        context: Context,
        container: Container?,
        envVars: com.winlator.core.envvars.EnvVars?,
    ): BundleContents {
        val snapshot = CrashContext.snapshot()
        val sessionId = LaunchTrace.session()
        val report = LaunchReport.build(context, container, snapshot)

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
            appendLine("This bundle contains no passwords, tokens or account identifiers.")
            appendLine("Do not paste it into public channels before reviewing it.")
        }

        val containerConfig = container?.let {
            val json = it.getContainerJson()
            if (json.isNullOrBlank()) "{}" else EnvRedactor.redactText(json)
        } ?: "{}"

        val redactedEnv = envVars?.let { EnvRedactor.redact(it) } ?: "[]"

        val events = LaunchTrace.toJsonLines()

        return BundleContents(
            summary = summary,
            launchReport = report,
            eventsJsonl = events,
            redactedEnv = redactedEnv,
            containerConfig = containerConfig,
            wineLog = lastWineLog(context)?.let { f ->
                if (f.length() > 0) EnvRedactor.redactText(f.readText().take(200_000)) else null
            },
            logcatFiltered = CrashHandler.getAppLogs(512),
        )
    }

    /** Writes the bundle to [destZip]. Call on a background thread. */
    fun export(
        context: Context,
        container: Container?,
        envVars: com.winlator.core.envvars.EnvVars?,
        destZip: File,
    ): File {
        val contents = collect(context, container, envVars)
        destZip.parentFile?.mkdirs()

        ZipOutputStream(destZip.outputStream()).use { zos ->
            writeEntry(zos, "summary.txt", contents.summary)
            writeEntry(zos, "launch-report.json", contents.launchReport.toString(2))
            writeEntry(zos, "events.jsonl", contents.eventsJsonl)
            writeEntry(zos, "redacted-env.txt", contents.redactedEnv)
            writeEntry(zos, "container-config.json", contents.containerConfig)
            contents.wineLog?.let { writeEntry(zos, "wine.log", it) }
            writeEntry(zos, "logcat-filtered.txt", contents.logcatFiltered)
        }
        return destZip
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun lastWineLog(context: Context): File? {
        val dir = File(context.getExternalFilesDir(null), "wine_logs")
        return dir.listFiles()?.maxByOrNull { it.lastModified() }
    }
}
