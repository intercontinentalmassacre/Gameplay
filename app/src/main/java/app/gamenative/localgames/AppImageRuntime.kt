package app.gamenative.localgames

import android.content.Context
import android.system.Os
import app.gamenative.PluviaApp
import app.gamenative.data.GameSource
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.utils.AssetUtils
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.UpdateInstaller
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.TarCompressorUtils
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.EnvironmentComponent
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.PulseAudioComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.XServer
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

internal object AppImageRuntime {
    private const val ARCHIVE = "imagefs_gamenative.txz"
    private const val SHA256 = "3c16c82a1948d3804de45e75c41ff455f1a393a36349af90eae06fd5b93d248b"
    private val installLock = Mutex()

    fun selected(appId: String, container: Container) =
        container.executablePath.endsWith(".AppImage", ignoreCase = true) &&
            ContainerUtils.extractGameSourceFromContainerId(appId) == GameSource.CUSTOM_GAME

    private fun root(context: Context) = File(context.filesDir, "appimage-linux-v1-${SHA256.take(12)}")

    private fun source(container: Container): File {
        val folder = requireNotNull(ContainerUtils.getADrivePath(container.drives)) { "The game folder is missing" }
        val game = File(folder, container.executablePath).canonicalFile
        require(game.toPath().startsWith(File(folder).canonicalFile.toPath()) && game.isFile) {
            "The AppImage is missing from the game folder"
        }
        return game
    }

    suspend fun prepare(context: Context, container: Container, progress: (Float) -> Unit) {
        val inspection = AppImageInspector.inspect(source(container))
        require(inspection.kind == ExecutableKind.ARM64_APPIMAGE) { inspection.reason.orEmpty() }
        installLock.withLock {
            val root = root(context)
            if (root.isDirectory) return
            val staging = Files.createTempDirectory(context.filesDir.toPath(), "appimage-install-").toFile()
            try {
                val cached = File(context.filesDir, ARCHIVE)
                val archive = cached.takeIf { it.isFile && UpdateInstaller.sha256(it) == SHA256 }
                    ?: File(staging, ARCHIVE).also {
                        SteamService.fetchFileWithFallback(ARCHIVE, it, context, progress)
                        check(UpdateInstaller.sha256(it) == SHA256) { "The Linux runtime download failed verification" }
                    }
                // Only the native userspace is used. The same archive also contains Windows components.
                val extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, staging) { file, _ ->
                    val path = file.toPath().normalize().let { staging.toPath().relativize(it).toString() }
                    file.takeIf {
                        path in setOf("bin", "etc", "lib", "usr", "share") ||
                            listOf("usr/lib", "usr/etc", "usr/bin", "usr/share").any { path == it || path.startsWith("$it/") }
                    }
                }
                check(extracted && File(staging, "lib/ld-linux-aarch64.so.1").isFile) { "Could not install the Linux runtime" }
                if (archive.parentFile == staging) archive.delete()
                // The image embeds these guest prefixes in ELF interpreters and library runpaths.
                for (pkg in listOf("app.gamenative", "com.winlator")) {
                    val files = File(staging, "data/data/$pkg/files").apply { mkdirs() }
                    Os.symlink("/", File(files, "imagefs").path)
                }
                // The shell's PT_INTERP also has a literal trailing bracket.
                Os.symlink("ld-linux-aarch64.so.1", File(staging, "usr/lib/ld-linux-aarch64.so.1]").path)
                for (path in listOf("tmp", "run/game", "home/game")) check(File(staging, path).mkdirs())
                // The bundled libxcb has /usr/tmp/.X11-unix compiled into its transport.
                Os.symlink("../tmp", File(staging, "usr/tmp").path)
                check(staging.renameTo(root)) { "Could not activate the Linux runtime" }
            } finally {
                FileUtils.delete(staging)
            }
        }
    }

    fun start(context: Context, container: Container, xServer: XServer, onError: ((String) -> Unit)?): XEnvironment {
        val root = root(context)
        require(root.isDirectory) { "The Linux runtime is missing" }
        AssetUtils.extractComponentsWithVersionCheck(
            listOf("pulseaudio-gamenative-20260612.tzst" to File(context.filesDir, "pulseaudio")),
            context.assets, TarCompressorUtils.Type.ZSTD,
        )
        val environment = XEnvironment(context, ImageFs.find(root))
        environment.addComponent(XServerComponent(xServer, UnixSocketConfig.createSocket(root.path, UnixSocketConfig.XSERVER_PATH)))
        environment.addComponent(PulseAudioComponent(UnixSocketConfig.createSocket(root.path, UnixSocketConfig.PULSE_SERVER_PATH), container.pulseaudioLowLatency))
        val home = File(container.rootDir, "appimage-home").apply { mkdirs() }
        environment.addComponent(AppImageProcess(source(container), home, onError))
        try {
            environment.startEnvironmentComponents()
        } catch (error: Exception) {
            environment.stopEnvironmentComponents()
            throw error
        }
        return environment
    }
}

private class AppImageProcess(
    private val source: File,
    private val home: File,
    private val onError: ((String) -> Unit)?,
) : EnvironmentComponent() {
    @Volatile private var process: Process? = null

    override fun start() {
        val context = environment.context
        val root = environment.imageFs.rootDir
        val native = File(context.applicationInfo.nativeLibraryDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "appimage-run-").toFile()
        try {
            // Shared Android storage is noexec. Keep the executable and extracted files private for this run.
            val image = source.copyTo(File(staging, "game.AppImage"))
            val inspection = AppImageInspector.inspect(image)
            require(inspection.kind == ExecutableKind.ARM64_APPIMAGE) { inspection.reason.orEmpty() }
            check(image.setExecutable(true, true)) { "Could not prepare the AppImage executable" }
            val builder = ProcessBuilder(
                File(native, "libproot.so").path, "--kill-on-exit", "-r", root.path,
                "-w", "/home/game", "-b", "/dev", "-b", "/proc",
                "-b", "${home.path}:/home/game", "-b", "${staging.path}:/run/game",
                "/run/game/game.AppImage",
            ).redirectErrorStream(true)
            builder.environment().apply {
                clear()
                put("PROOT_LOADER", File(native, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", staging.path)
                put("TMPDIR", "/run/game")
                put("HOME", "/home/game")
                put("PATH", "/usr/bin:/bin")
                put("LD_LIBRARY_PATH", "/usr/lib:/usr/lib/pulseaudio")
                put("DISPLAY", ":0")
                put("PULSE_SERVER", "unix:${UnixSocketConfig.PULSE_SERVER_PATH}")
                put("APPIMAGE_EXTRACT_AND_RUN", "1")
            }
            val running = builder.start()
            process = running
            thread(name = "AppImage-session", isDaemon = true) {
                try {
                    var lastLine = ""
                    running.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach {
                            if (it.isNotBlank()) lastLine = it.take(200)
                            Timber.tag("AppImage").d("%s", it)
                        }
                    }
                    val status = running.waitFor()
                    if (process === running) {
                        if (status != 0) onError?.invoke("AppImage exited with status $status. $lastLine".trimEnd())
                        PluviaApp.events.emit(AndroidEvent.GuestProgramTerminated)
                    }
                } finally {
                    FileUtils.delete(staging)
                }
            }
        } catch (error: Exception) {
            FileUtils.delete(staging)
            throw error
        }
    }

    override fun stop() {
        val running = process
        process = null
        running?.destroy()
    }
}
