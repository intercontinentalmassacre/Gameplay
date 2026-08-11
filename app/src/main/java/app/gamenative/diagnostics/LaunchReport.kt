package app.gamenative.diagnostics

import android.content.Context
import android.os.Build
import app.gamenative.CrashContext
import app.gamenative.CrashContextSnapshot
import com.winlator.container.Container
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a redacted `launch-report.json` for the active (or last) launch.
 * Never includes paths, passwords, cookies, tokens or Steam session secrets.
 */
object LaunchReport {

    fun build(
        context: Context,
        container: Container?,
        crashContext: CrashContextSnapshot? = null,
    ): JSONObject {
        val ctx = crashContext ?: CrashContext.snapshot()
        val json = JSONObject()
        val sessionId = LaunchTrace.session() ?: "unknown"

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        json.put("schemaVersion", 1)
        json.put("sessionId", sessionId)
        json.put("generatedAt", java.time.Instant.now().toString())
        json.put("gameplayVersion", packageInfo?.versionName ?: "unknown")
        json.put("gameplayVersionCode", packageInfo?.longVersionCode?.toString() ?: "unknown")

        json.put("device", JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("pageSize", pageSize())
            .put("kernel", Build.VERSION.RELEASE)
        )

        val gpuInfo = JSONObject()
            .put("gpu", ctx.gpu ?: runCatching {
                com.winlator.core.GPUInformation.getRenderer(context)
            }.getOrNull() ?: "unknown")
        json.put("gpu", gpuInfo)

        json.put("game", JSONObject()
            .put("appId", ctx.appId ?: "unknown")
            .put("name", ctx.gameName ?: "unknown")
        )

        json.put("launch", JSONObject()
            .put("stage", ctx.launchStage)
            .put("containerId", ctx.containerId ?: "unknown")
            .put("runtime", ctx.runtime ?: "unknown")
        )

        if (container != null) {
            json.put("container", JSONObject()
                .put("id", container.id)
                .put("name", container.name)
                .put("wineVersion", container.getWineVersion())
                .put("graphicsDriver", container.getGraphicsDriver())
                .put("graphicsDriverVersion", container.getGraphicsDriverVersion())
                .put("dxWrapper", container.getDXWrapper())
                .put("audioDriver", container.getAudioDriver())
                .put("cpuBackend", container.getEmulator())
                .put("screenSize", container.screenSize)
                .put("box64Preset", container.box64Preset)
                .put("fexCorePreset", container.fexCorePreset)
                .put("woW64", container.isWoW64Mode)
            )
        }

        val events = LaunchTrace.eventsSnapshot()
        json.put("events", JSONArray().apply {
            events.forEach { e ->
                put(JSONObject()
                    .put("stage", e.stage.name)
                    .put("durationMs", e.durationMs)
                    .put("result", e.result))
            }
        })

        return json
    }

    private fun pageSize(): Long {
        return try {
            java.io.BufferedReader(
                java.io.InputStreamReader(Runtime.getRuntime().exec("getconf PAGE_SIZE").inputStream)
            ).use { it.readLine()?.trim()?.toLongOrNull() ?: -1L }
        } catch (_: Exception) {
            -1L
        }
    }

    /** Persists the report next to the session trace. */
    fun write(context: Context, container: Container?): File {
        val sessionId = LaunchTrace.session() ?: "session"
        val dir = LaunchTrace.dir() ?: context.getExternalFilesDir(null)!!
        val file = File(dir, "$sessionId.launch-report.json")
        file.writeText(build(context, container).toString(2))
        return file
    }
}
