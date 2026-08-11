package app.gamenative

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.gamenative.diagnostics.LaunchTrace
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A local running crash handler.
 * Any uncaught exceptions will be saved located locally in a text file, aka: Crash Report.
 * File location: /<user storage>/Android/data/app.gamenative/files/crash_logs/
 */
class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val LOG_CAT_COUNT = 256
        private const val CRASH_FILE_HISTORY_COUNT = 1
        private val bearerTokenPattern = Regex("(?i)\\bBearer\\s+\\S+")
        private val sensitiveAssignmentPattern = Regex(
            "(?i)([\\\"']?(?:authorization|proxy-authorization|cookie|set-cookie|x-api-key|api-key|api[_-]?key|api[_-]?token|access[_-]?token|refresh[_-]?token|password|passwd|secret|token)[\\\"']?\\s*[:=]\\s*)(?:[\\\"'][^\\\"']*[\\\"']|[^\\s,;}]*)",
        )
        private val sensitiveQueryPattern = Regex(
            "(?i)([?&](?:access[_-]?token|refresh[_-]?token|api[_-]?key|token|signature)=)[^&\\s]+",
        )

        val timestamp: String
            get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

        /**
         * Logcat command
         */
        private fun logcatCommand(count: Int): String = "logcat -d -t $count --pid=${android.os.Process.myPid()}"

        fun initialize(context: Context) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            val crashHandler = CrashHandler(context.applicationContext, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
            LaunchTrace.init(context.applicationContext)
        }

        /**
         * Helper method to get logcat info live
         */
        fun getAppLogs(lineCount: Int = LOG_CAT_COUNT): String {
            var process: Process? = null
            var reader: BufferedReader? = null

            return try {
                process = Runtime.getRuntime().exec(logcatCommand(lineCount))
                reader = BufferedReader(InputStreamReader(process.inputStream))

                val log = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    log.append(line).append("\n")
                }

                sanitizeLogcat(log.toString())
            } catch (e: Exception) {
                "Failed to capture logs: ${e.message}"
            } finally {
                reader?.close()
                process?.destroy()
            }
        }

        internal fun sanitizeLogcat(log: String): String {
            return log
                .replace(bearerTokenPattern) { "Bearer <redacted>" }
                .replace(sensitiveAssignmentPattern) { "${it.groupValues[1]}<redacted>" }
                .replace(sensitiveQueryPattern) { "${it.groupValues[1]}<redacted>" }
        }

        /** Returns the most recent crash report file, or null if none exist. */
        fun latestCrashFile(context: Context): File? {
            val dir = File(context.getExternalFilesDir(null), "crash_logs")
            return dir.listFiles()
                ?.filter { it.name.startsWith("pluvia_crash_") && it.isFile }
                ?.maxByOrNull { it.lastModified() }
        }

        /**
         * Builds an email intent for reporting a crash. The most recent crash
         * report is attached via FileProvider; the email body contains a short
         * summary of the crash and device info. Returns null if no mail app
         * handles the request or no crash report exists.
         */
        fun buildCrashEmailIntent(context: Context): Intent? {
            val crashFile = latestCrashFile(context) ?: return null
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                crashFile,
            )
            val deviceInfo = buildString {
                appendLine("App Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(Constants.Misc.CRASH_REPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, "Gameplay Crash Report - ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
                putExtra(Intent.EXTRA_TEXT, deviceInfo)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return intent.resolveActivity(context.packageManager)?.let { intent }
        }
    }

    private val crashFileDir by lazy {
        File(context.getExternalFilesDir(null), "crash_logs").apply {
            if (!exists()) mkdirs()
        }
    }

    private val recentLogcat: String
        get() = getAppLogs(LOG_CAT_COUNT)

    private val cleanupOldCrashFiles: () -> Unit = {
        crashFileDir.listFiles()?.let { files ->
            if (files.size > CRASH_FILE_HISTORY_COUNT) {
                files.sortByDescending { it.lastModified() }
                files.drop(CRASH_FILE_HISTORY_COUNT).forEach { it.delete() }
            }
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        PrefManager.recentlyCrashed = true

        saveCrashToFile(throwable)
        LaunchTrace.finish("failed")
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashToFile(throwable: Throwable) {
        try {
            val stackTrace = StringWriter().apply {
                val pw = PrintWriter(this)
                throwable.printStackTrace(pw)
            }.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val launchContext = CrashContext.snapshot()

            val crashReport = buildString {
                appendLine("Timestamp: $timestamp")
                appendLine("App Version: ${packageInfo.versionName} (${packageInfo.longVersionCode})")
                appendLine()
                appendLine("---------- Device Information ----------")
                appendLine("${android.os.Build.MANUFACTURER} - ${android.os.Build.BRAND} - ${android.os.Build.MODEL}")
                appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
                appendLine()
                appendLine("---------- Launch Context ----------")
                appendLine("Game: ${launchContext.gameName ?: "unknown"}")
                appendLine("App ID: ${launchContext.appId ?: "unknown"}")
                appendLine("Container: ${launchContext.containerId ?: "unknown"}")
                appendLine("Runtime: ${launchContext.runtime ?: "unknown"}")
                appendLine("GPU: ${launchContext.gpu ?: "unknown"}")
                appendLine("Launch stage: ${launchContext.launchStage}")
                appendLine()
                appendLine("---------- Cause ----------")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${CrashHandler.sanitizeLogcat(throwable.message.orEmpty())}")
                appendLine()
                appendLine("---------- Stack Trace ----------")
                appendLine(CrashHandler.sanitizeLogcat(stackTrace))
                appendLine()
                appendLine("---------- Logcat (redacted) ----------")
                appendLine(recentLogcat)
            }

            File(crashFileDir, "pluvia_crash_$timestamp.txt").writeText(crashReport)

            runCatching {
                app.gamenative.diagnostics.LaunchReport.write(context, container = null)
            }

            cleanupOldCrashFiles()
        } catch (e: Exception) {
            defaultHandler?.uncaughtException(Thread.currentThread(), throwable)
        }
    }
}
