package org.buran.runtime

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

internal const val GAMEPLAY_SESSION_STATUS = "sessionStatus"
internal const val GAMEPLAY_DUAL_SCREEN = "dualScreen"
internal const val GAMEPLAY_QUICK_MENU = "org.buran.runtime.GAMEPLAY_QUICK_MENU"
internal const val GAMEPLAY_QUICK_MENU_BUTTON = "org.buran.runtime.GAMEPLAY_QUICK_MENU_BUTTON"
internal const val GAMEPLAY_RESTART_GAME = "restartGame"
internal const val GAMEPLAY_STEAM_APP_ID = "steamAppId"

/** Launches a local installation and returns the Gamescope session result to Gameplay. */
class GameplayLaunchActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancellation = CancellationSignal()
    private var uriStream: ParcelFileDescriptor.AutoCloseInputStream? = null
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller = callingPackage
        val archive = intent.data
        val direct = isBuranEmbedded && caller == packageName
        if (intent.action != PLAY_GAME || (isBuranEmbedded && !direct) ||
            (!direct && (caller !in gameplayPackages || archive?.scheme != "content" ||
                archive.authority != "$caller.buran.files" ||
                intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
                checkUriPermission(archive, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED))
        ) {
            finish()
            return
        }
        launched = savedInstanceState?.getBoolean("launched") == true
        val title = intent.getStringExtra("title")?.take(120)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = if (title.isNullOrBlank()) "Подготовка игры…" else "Подготовка игры: $title"
        })
        if (launched) return
        val key = intent.getStringExtra("installKey")
        val revision = intent.getStringExtra("revision")
        val executable = intent.getStringExtra("executable")
        if (!validToken(key) || (!direct && !validToken(revision)) || executable == null ||
            !validRelativePath(executable) || !executable.endsWith(".exe", ignoreCase = true)
        ) {
            fail("Недопустимые параметры запуска игры")
            return
        }
        val installationKey = "$caller:$key"
        val gameDirectory = intent.getStringExtra("gameDirectory")
        val workingDirectory = if (direct) intent.getStringExtra("workingDirectory") else null
        val steamAppId = if (direct && intent.hasExtra(GAMEPLAY_STEAM_APP_ID)) {
            intent.getIntExtra(GAMEPLAY_STEAM_APP_ID, -1)
        } else null
        worker.execute {
            val result = runCatching {
                if (direct) {
                    val directory = File(requireNotNull(gameDirectory))
                    require(directory.isAbsolute && directory.isDirectory) { "Папка игры недоступна" }
                    val root = directory.canonicalFile
                    val bits = gameExecutableBits(this, root, executable)
                    require(workingDirectory == null || workingDirectory == "." || validRelativePath(workingDirectory)) {
                        "Недопустимый рабочий каталог игры"
                    }
                    ExternalWindowsLaunch.resolve(
                        installationKey, root, executable, bits, workingDirectory = workingDirectory, steamAppId = steamAppId,
                        steamUserDirectory = intent.getStringExtra("steamUserDirectory")?.let(::File),
                        steamRemoteDirectory = intent.getStringExtra("steamRemoteDirectory")?.let(::File),
                    )
                } else {
                    prepare(checkNotNull(archive), installationKey, checkNotNull(revision), executable)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(::startGame).onFailure { fail(it.localizedMessage ?: "Не удалось подготовить игру") }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        super.onSaveInstanceState(outState)
    }

    private fun prepare(uri: Uri, key: String, revision: String, executable: String): ExternalWindowsLaunch =
        synchronized(importLock) {
            cancellation.throwIfCanceled()
            val root = buranFilesDir.resolve("gameplay-installs/${digest(key)}")
            val destination = root.resolve(digest(revision))
            if (destination.isDirectory) {
                val bits = gameExecutableBits(this, destination, executable)
                return@synchronized ExternalWindowsLaunch.resolve(key, destination, executable, bits)
            }
            Files.createDirectories(root.toPath())
            val staging = Files.createTempDirectory(root.toPath(), ".import-").toFile()
            try {
                extract(uri, staging)
                val bits = gameExecutableBits(this, staging, executable)
                cancellation.throwIfCanceled()
                Files.move(staging.toPath(), destination.toPath(), ATOMIC_MOVE)
                ExternalWindowsLaunch.resolve(key, destination, executable, bits)
            } finally {
                staging.deleteRecursively()
            }
        }

    private fun extract(uri: Uri, directory: File) {
        val descriptor = checkNotNull(contentResolver.openFileDescriptor(uri, "r", cancellation)) {
            "Не удалось открыть файлы игры"
        }
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        synchronized(this) {
            if (cancellation.isCanceled) {
                input.close()
                cancellation.throwIfCanceled()
            }
            uriStream = input
        }
        try {
            val source = BoundedArchive(input)
            ZipInputStream(source).use { zip ->
                val buffer = ByteArray(64 * 1024)
                var entries = 0
                var size = 0L
                while (true) {
                    cancellation.throwIfCanceled()
                    val entry = zip.nextEntry ?: break
                    require(++entries <= MAX_ENTRIES) { "Слишком много файлов в архиве игры" }
                    val relative = if (entry.isDirectory) entry.name.dropLast(1) else entry.name
                    require(validRelativePath(relative)) { "Недопустимый путь в архиве игры" }
                    val output = directory.resolve(relative).toPath()
                    if (entry.isDirectory) {
                        Files.createDirectories(output)
                        require(zip.read() == -1) { "Каталог в архиве содержит данные" }
                    } else {
                        Files.createDirectories(output.parent)
                        Files.newOutputStream(output, CREATE_NEW, WRITE).use { file ->
                            while (true) {
                                cancellation.throwIfCanceled()
                                val count = zip.read(buffer)
                                if (count == -1) break
                                size += count
                                require(size <= MAX_EXPANDED_BYTES) { "Слишком большой архив игры" }
                                file.write(buffer, 0, count)
                            }
                        }
                    }
                }
                // The sender reports source failures through the reliable pipe, after its final ZIP entry.
                while (source.read(buffer) != -1) cancellation.throwIfCanceled()
                descriptor.checkError()
            }
        } finally {
            synchronized(this) { uriStream = null }
        }
    }

    private fun startGame(request: ExternalWindowsLaunch) {
        val bionicSteam = intent.getBundleExtra(BionicSteamRuntime.EXTRA).takeIf {
            isBuranEmbedded
        }
        val cpuBundle = intent.getBundleExtra(BuranCpuSelection.EXTRA).takeIf { isBuranEmbedded }
        val cpu = BuranCpuSelection.resolve(this, cpuBundle)
        val engine = when {
            request.game.bits == 64 && bionicSteam == null && request.steamAppId == null &&
                cpu.fexSystem32 != null && WindowsProbe.runtimeAvailable(this) -> WindowsEngine.FEX
            request.game.bits == 64 -> WindowsEngine.BOX64
            else -> WindowsEngine.BOX86
        }
        if (bionicSteam == null && engine !in WindowsEngine.available()) {
            fail("Транслятор для ${request.game.bits}-битной игры недоступен")
            return
        }
        try {
            GamescopeActivity.launchExternal(
                this, request, desktop = isBuranEmbedded && intent.getBooleanExtra("wineDesktop", false),
                engine = engine, gameDxvk = bionicSteam == null && engine == WindowsEngine.BOX64,
                gameArguments = if (isBuranEmbedded) intent.getStringArrayListExtra("gameArguments").orEmpty() else emptyList(),
                onLaunchRejected = ::fail,
                readyToDispatch = { window.decorView.windowVisibility == View.VISIBLE },
            ) { gameIntent ->
                try {
                    if (bionicSteam != null) gameIntent.putExtra(BionicSteamRuntime.EXTRA, bionicSteam)
                    if (cpuBundle != null) gameIntent.putExtra(BuranCpuSelection.EXTRA, cpuBundle)
                    if (isBuranEmbedded) intent.getBundleExtra("buranGraphics")?.let {
                        gameIntent.putExtra("buranGraphics", it)
                    }
                    if (isBuranEmbedded) intent.getBundleExtra(SteamInstallDependencies.EXTRA)?.let {
                        gameIntent.putExtra(SteamInstallDependencies.EXTRA, it)
                    }
                    @Suppress("DEPRECATION")
                    val sessionStatus = intent.getParcelableExtra<ResultReceiver>(GAMEPLAY_SESSION_STATUS)
                    if (sessionStatus != null) gameIntent.putExtra(GAMEPLAY_SESSION_STATUS, sessionStatus)
                    gameIntent.putExtra(GAMEPLAY_DUAL_SCREEN, intent.getBooleanExtra(GAMEPLAY_DUAL_SCREEN, false))
                    gameIntent.putExtra("touchSensitivity", intent.getFloatExtra("touchSensitivity", 1f))
                    gameIntent.putExtra("buranFramegen2x", isBuranEmbedded && intent.getBooleanExtra("buranFramegen2x", false))
                    @Suppress("DEPRECATION")
                    val menu = intent.getParcelableExtra<PendingIntent>(GAMEPLAY_QUICK_MENU)
                    if (menu != null && menu.creatorPackage == callingPackage && (Build.VERSION.SDK_INT < 31 || menu.isActivity)) {
                        gameIntent.putExtra(GAMEPLAY_QUICK_MENU, menu)
                    }
                    gameIntent.putExtra(GAMEPLAY_QUICK_MENU_BUTTON, intent.getIntExtra(GAMEPLAY_QUICK_MENU_BUTTON, 0))
                    @Suppress("DEPRECATION")
                    startActivityForResult(gameIntent, GAME_REQUEST)
                    launched = true
                } catch (error: Exception) {
                    fail(error.localizedMessage ?: "Не удалось запустить игру")
                }
            }
        } catch (error: Exception) {
            fail(error.localizedMessage ?: "Не удалось запустить игру")
        }
    }

    @Deprecated("Relays the Gamescope activity result to Gameplay")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GAME_REQUEST) {
            if (resultCode == RESULT_OK && data?.getBooleanExtra(GAMEPLAY_RESTART_GAME, false) == true) {
                if (data.hasExtra("buranFramegen2x")) {
                    intent.putExtra("buranFramegen2x", data.getBooleanExtra("buranFramegen2x", false))
                }
                launched = false
                recreate()
                return
            }
            setResult(resultCode, data)
            finish()
        }
    }

    private fun fail(message: String) {
        setResult(RESULT_OK, Intent().putExtra("outcome", "failed").putExtra("message", message))
        finish()
    }

    override fun onDestroy() {
        cancellation.cancel()
        worker.shutdownNow()
        synchronized(this) { runCatching { uriStream?.close() } }
        super.onDestroy()
    }

    private class BoundedArchive(input: InputStream) : FilterInputStream(input) {
        private var bytes = 0L

        override fun read(): Int = super.read().also { if (it != -1) add(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) add(it) }

        private fun add(count: Int) {
            bytes += count
            require(bytes <= MAX_ARCHIVE_BYTES) { "Слишком большой архив игры" }
        }
    }

    private companion object {
        const val PLAY_GAME = "org.buran.runtime.PLAY_GAME"
        const val GAME_REQUEST = 1
        const val MAX_ENTRIES = 200_000
        const val MAX_EXPANDED_BYTES = 64L * 1024 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 65L * 1024 * 1024 * 1024
        val gameplayPackages = setOf("app.gameplay", "app.gameplay.debug", "app.gameplay.dev", "app.gameplay.gold")
        val importLock = Any()

        fun validToken(token: String?) =
            !token.isNullOrBlank() && token.length <= 255 && token.none(Char::isISOControl)

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun validRelativePath(path: String): Boolean =
            path.toByteArray(Charsets.UTF_8).size <= 4095 && path.split('/').all { name ->
                name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 255 &&
                    !name.endsWith('.') && !name.endsWith(' ') &&
                    name.none { it.isISOControl() || it in "\\:<>\"|?*" }
            }
    }
}
