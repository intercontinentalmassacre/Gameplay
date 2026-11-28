package org.buran.runtime

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Opens the compressed APK asset and passes its bytes to the Rust installer. */
internal object NativeRuntimeInstaller {
    fun install(context: Context, kind: String, asset: String, destination: File, stamp: String, vararg args: String) {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libburan.so")
        val log = context.buranFilesDir.resolve("$kind-install.txt")
        context.assets.open(asset).use { source ->
            val child = ProcessBuilder(binary.path, "install-runtime", kind, destination.path, stamp, *args)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                var streamError: IOException? = null
                try {
                    child.outputStream.use { target ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException()
                            val bytes = source.read(buffer)
                            if (bytes < 0) break
                            target.write(buffer, 0, bytes)
                        }
                    }
                } catch (error: IOException) {
                    streamError = error
                }
                check(child.waitFor(90, TimeUnit.SECONDS)) { "Установка $kind не завершилась" }
                check(child.exitValue() == 0) { log.readText().takeLast(2000).trim().ifEmpty { "Не удалось установить $kind" } }
                if (streamError != null) throw streamError
            } finally {
                if (child.isAlive) child.destroyForcibly()
            }
        }
    }
}
