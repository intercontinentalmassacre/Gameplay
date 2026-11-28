package org.buran.runtime.display

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.view.Surface
import org.buran.runtime.buranFilesDir
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

/** Owns capture and rendering. The native renderer is used only by the reader's worker. */
internal class FramegenOutput(
    private val context: Context,
    output: Surface,
    private val width: Int,
    private val height: Int,
    private val onActive: (FramegenOutput) -> Unit,
    private val onFailure: (FramegenOutput, Throwable) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
    val surface: Surface get() = reader.surface
    private val screen = try {
        copySurface(output)
    } catch (error: Exception) {
        reader.close()
        throw error
    }
    private val worker = HandlerThread("Buran frame generation")
    private val handler: Handler
    @Volatile private var stopping = false
    private var failed = false
    private var native = 0L
    private var presented = false
    private var lastTimestamp = Long.MIN_VALUE
    @Volatile private var generatedFrames = 0L
    private var sampledFrames = 0L
    private var sampledAt: Long? = null
    private var sampledFps: Int? = null

    /** Called by the session telemetry worker only, separate from the render worker. */
    fun sampleGeneratedFps(now: Long): Int? {
        val elapsed = sampledAt?.let { now - it }
        if (elapsed != null && elapsed < 1_000L) return sampledFps
        val frames = generatedFrames
        sampledFps = elapsed?.takeIf { it <= 2_000L }?.let {
            ((frames - sampledFrames) * 1000f / it).roundToInt()
        }
        sampledFrames = frames
        sampledAt = now
        return sampledFps
    }

    init {
        try {
            worker.start()
            handler = Handler(worker.looper)
            reader.setOnImageAvailableListener({ capture() }, handler)
        } catch (error: Exception) {
            worker.quitSafely()
            reader.close()
            screen.release()
            throw error
        }
    }

    private fun open() {
        val shaders = shaders(context)
        if (stopping) return
        native = FramegenNative.open(screen, shaders.path, width, height)
        check(native != 0L) { "Vulkan frame generation could not initialize" }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            context.buranFilesDir.resolve("vkfg-diagnostics").isFile
        ) FramegenNative.enableDiagnostics(native)
    }

    private fun capture() {
        if (stopping || failed) return
        try {
            val timestamp = reader.acquireLatestImage()?.use { image ->
                if (stopping) return
                check(image.width == width && image.height == height && image.format == PixelFormat.RGBA_8888) {
                    "Frame generation received an unexpected capture size or format"
                }
                val plane = image.planes.single()
                val pixels = plane.buffer.slice()
                val stride = plane.rowStride
                val step = plane.pixelStride
                val end = (height - 1L) * stride + (width - 1L) * step + 4
                check(pixels.isDirect && step >= 4 && stride.toLong() >= (width - 1L) * step + 4 &&
                    end <= pixels.remaining()
                ) { "Frame generation received an incomplete capture buffer" }
                val timestamp = image.timestamp.takeIf { it > 0L } ?: System.nanoTime()
                if (timestamp <= lastTimestamp) return
                lastTimestamp = timestamp
                // The first ImageReader frame confirms Gamescope switched away from the
                // real SurfaceView. Only then may a second Vulkan producer open that output.
                if (native == 0L) open()
                if (stopping) return
                val staged = FramegenNative.stage(native, pixels, stride, step)
                check(staged >= 0) { "Vulkan frame generation could not copy a frame" }
                if (staged == 0) return
                timestamp
            } ?: return
            // Return the capture buffer to Gamescope before presentation can block on vsync.
            if (stopping) return
            val generated = FramegenNative.present(native, timestamp)
            check(generated >= 0) { "Vulkan frame generation could not present a frame" }
            if (generated > 0) generatedFrames += generated
            if (!presented) {
                presented = true
                main.post { if (!stopping) onActive(this) }
            }
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        failed = true
        main.post { if (!stopping) onFailure(this, error) }
    }

    /** The compositor must detach from [surface] before closing the reader. Main thread only. */
    fun close(onClosed: () -> Unit) {
        if (stopping) return
        stopping = true
        handler.post {
            try {
                if (native != 0L) FramegenNative.close(native)
            } catch (error: Exception) {
                Log.w(TAG, "Frame generation cleanup failed", error)
            } catch (error: LinkageError) {
                Log.w(TAG, "Frame generation cleanup unavailable", error)
            } finally {
                try {
                    reader.close()
                } finally {
                    screen.release()
                    worker.quitSafely()
                    main.post(onClosed)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BuranFramegen"
        private const val SHADERS = "vkfg/shaders"

        fun isAvailable(context: Context): Boolean =
            runCatching { !context.assets.list(SHADERS).isNullOrEmpty() }.getOrDefault(false)

        private fun copySurface(surface: Surface): Surface {
            // SurfaceHolder can reuse its Java Surface during recreation. Retain this producer
            // generation until the render worker finishes, independently of the holder.
            val parcel = Parcel.obtain()
            try {
                surface.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                return Surface.CREATOR.createFromParcel(parcel)
            } finally {
                parcel.recycle()
            }
        }

        @Synchronized
        private fun shaders(context: Context): File {
            val names = context.assets.list(SHADERS).orEmpty().sorted()
            check(names.isNotEmpty() && names.all { it.matches(Regex("[a-z0-9_]+\\.spv")) }) {
                "Frame generation shaders are unavailable"
            }
            val cache = context.cacheDir.resolve("buran-framegen").apply { Files.createDirectories(toPath()) }
            val staging = Files.createTempDirectory(cache.toPath(), ".shaders-").toFile()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                for (name in names) {
                    digest.update(name.toByteArray(Charsets.UTF_8))
                    digest.update(0)
                    val file = staging.resolve(name)
                    DigestInputStream(context.assets.open("$SHADERS/$name"), digest).use { input ->
                        file.outputStream().use { output -> check(input.copyTo(output) > 0L) }
                    }
                    digest.update(0)
                    check(file.setReadOnly())
                }
                val revision = digest.digest().joinToString("") { "%02x".format(it) }
                val target = cache.resolve("shaders-$revision")
                if (!target.exists()) {
                    try {
                        Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE)
                    } catch (error: FileAlreadyExistsException) {
                        if (!target.isDirectory) throw error
                    }
                }
                check(names.all { target.resolve(it).let { file -> file.isFile && file.length() > 0L } })
                return target
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
