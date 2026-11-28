package org.buran.runtime.display

import android.content.Context
import org.buran.runtime.buranDisplayRoot
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.min

internal class GuestDisplay(private val context: Context) : AutoCloseable {
    private val root = context.buranDisplayRoot
    private val tmp = X11Connection.sharedTmp(context)
    private val framebuffer = tmp.resolve("Xvfb_screen0")
    private val socket = tmp.resolve(".X11-unix/X$DISPLAY_NUMBER")
    private val authority = root.resolve("Xauthority")
    private val cookie = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val native = File(context.applicationInfo.nativeLibraryDir)
    private val input = Executors.newSingleThreadExecutor()
    private var pointer: XPointer? = null
    private var server: Process? = null
    @Volatile private var closed = false
    @Volatile private var source: XwdFrame? = null

    val view: View = object : View(context) {
        private val screen = RectF()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width / WIDTH.toFloat(), height / HEIGHT.toFloat())
            val left = (width - WIDTH * scale) / 2
            val top = (height - HEIGHT * scale) / 2
            screen.set(left, top, left + WIDTH * scale, top + HEIGHT * scale)
            source?.let { frame ->
                if (XvfbPixels.copy(frame.pixels, frame.stride, frame.bigEndian, bitmap)) {
                    canvas.drawBitmap(bitmap, null, screen, paint)
                }
            }
            if (!closed) postInvalidateDelayed(33)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (closed || screen.isEmpty) return false
            val type = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (screen.contains(event.x, event.y)) 4 else return false
                MotionEvent.ACTION_MOVE -> 6
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 5
                else -> return true
            }
            val x = ((event.x - screen.left) * WIDTH / screen.width()).toInt().coerceIn(0, WIDTH - 1)
            val y = ((event.y - screen.top) * HEIGHT / screen.height()).toInt().coerceIn(0, HEIGHT - 1)
            sendPointer(type, x, y)
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }.apply { setBackgroundColor(0xff000000.toInt()) }

    fun start(): Map<String, String> {
        check(!closed && server == null)
        check(displayLease.tryAcquire(5, TimeUnit.SECONDS)) {
            "Предыдущая сессия Xvfb ещё завершается"
        }
        val log = tmp.resolve("xvfb.log")
        val process = try {
            tmp.mkdirs()
            installData()
            X11Connection.writeAuthority(authority, cookie, DISPLAY_NUMBER)
            framebuffer.delete()
            synchronized(this) {
                check(!closed && server == null) { "Экран Wine закрыт" }
                ProcessBuilder(
                    native.resolve("libburan_xvfb.so").path, ":$DISPLAY_NUMBER",
                    "-screen", "0", "${WIDTH}x${HEIGHT}x24", "-nolisten", "tcp",
                    "-auth", authority.path, "-fbdir", tmp.path, "-fp", "built-ins",
                    "-extension", "GLX", "-extension", "MIT-SHM", "-noreset"
                ).apply {
                    environment()["LD_LIBRARY_PATH"] = native.path
                    environment()["TMPDIR"] = tmp.path
                    redirectErrorStream(true)
                    redirectOutput(log)
                }.start().also { server = it }
            }
        } catch (error: Throwable) {
            displayLease.release()
            throw error
        }

        repeat(150) {
            if (closed) error("Экран Wine закрыт")
            if (!process.isAlive) error("Xvfb: ${log.readText().takeLast(2000)}")
            if (framebuffer.isFile && runCatching { XPointer(socket, cookie).close() }.isSuccess) {
                val frame = runCatching { mapFramebuffer(framebuffer) }.getOrNull()
                if (frame != null) {
                    synchronized(this) {
                        check(!closed) { "Экран Wine закрыт" }
                        source = frame
                    }
                    view.postInvalidate()
                    return mapOf(
                        "DISPLAY" to ":$DISPLAY_NUMBER",
                        "TMPDIR" to tmp.path,
                        "XAUTHORITY" to authority.path,
                        "BURAN_X11_SOCKET" to socket.path
                    )
                }
            }
            Thread.sleep(50)
        }
        error("Xvfb: ${log.readText().takeLast(2000)}")
    }

    private fun installData() {
        ZipInputStream(context.assets.open("display-data.zip").buffered()).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                val file = root.resolve(entry.name)
                check(file.canonicalPath.startsWith(root.canonicalPath + "/"))
                file.parentFile!!.mkdirs()
                if (entry.isDirectory) file.mkdirs()
                else file.outputStream().buffered().use { archive.copyTo(it) }
            }
        }
        val compiler = root.resolve("bin/xkbcomp")
        compiler.parentFile!!.mkdirs()
        compiler.delete()
        android.system.Os.symlink(native.resolve("libburan_xkbcomp.so").path, compiler.path)
    }

    @Synchronized private fun sendPointer(type: Int, x: Int, y: Int) {
        if (closed) return
        input.execute {
            try {
                val connection = pointer ?: XPointer(socket, cookie).also { pointer = it }
                connection.move(x, y)
                if (type != 6) connection.button(type)
            } catch (exception: Exception) {
                pointer?.close()
                pointer = null
                Log.w("BuranDisplay", "XTEST input failed", exception)
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        source = null
        input.execute { pointer?.close() }
        input.shutdown()
        server?.let { process ->
            shutdown.execute {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor()
                }
                displayLease.release()
            }
        }
        view.postInvalidate()
    }

    private data class XwdFrame(val pixels: ByteBuffer, val stride: Int, val bigEndian: Boolean)

    private companion object {
        const val DISPLAY_NUMBER = "99"
        val displayLease = Semaphore(1, true)
        val shutdown = Executors.newSingleThreadExecutor()
        const val WIDTH = 960
        const val HEIGHT = 540

        fun mapFramebuffer(file: File): XwdFrame {
            RandomAccessFile(file, "r").use { input ->
                val header = IntArray(25) { input.readInt() }
                check(header[1] == 7 && header[2] == 2 && header[3] == 24)
                check(header[4] == WIDTH && header[5] == HEIGHT && header[6] == 0 && header[11] == 32)
                val stride = header[12]
                check(stride >= WIDTH * 4 && header[14] == 0xff0000 &&
                    header[15] == 0xff00 && header[16] == 0xff)
                check(header[7] in 0..1 && header[0] >= 100 && header[19] >= 0)
                val offset = header[0].toLong() + header[19] * 12L
                val length = stride.toLong() * HEIGHT
                check(length <= Int.MAX_VALUE && offset + length <= input.length())
                val pixels = input.channel.map(FileChannel.MapMode.READ_ONLY, offset, length)
                return XwdFrame(pixels, stride, header[7] == 1)
            }
        }
    }

    private class XPointer(path: File, cookie: ByteArray) : AutoCloseable {
        private val connection = X11Connection.connect(path, cookie)
        private val opcode: Byte
        private val output: java.io.OutputStream

        init {
            try {
                output = connection.outputStream
                val response = DataInputStream(connection.inputStream)
                val query = packet(16)
                query.put(98.toByte()).put(0.toByte()).putShort(4).putShort(5).putShort(0)
                    .put("XTEST".toByteArray())
                output.write(query.array())
                val result = ByteArray(32).also { response.readFully(it) }
                check(result[0].toInt() == 1 && result[8].toInt() == 1) { "XTEST unavailable" }
                opcode = result[9]
            } catch (exception: Exception) {
                connection.close()
                throw exception
            }
        }

        fun move(x: Int, y: Int) = fakeInput(6, 0, x, y)

        fun button(type: Int) = fakeInput(type, 1, 0, 0)

        private fun fakeInput(type: Int, detail: Int, x: Int, y: Int) {
            val request = packet(36)
            request.put(opcode).put(2.toByte()).putShort(9).put(type.toByte()).put(detail.toByte())
            request.putShort(24, x.toShort()).putShort(26, y.toShort())
            output.write(request.array())
        }

        private fun packet(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

        override fun close() = connection.close()
    }
}


private object XvfbPixels {
    init { System.loadLibrary("buran_xvfb_pixels") }

    @JvmStatic external fun copy(pixels: ByteBuffer, stride: Int, bigEndian: Boolean, bitmap: Bitmap): Boolean
}
