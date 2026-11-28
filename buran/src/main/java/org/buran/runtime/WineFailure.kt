package org.buran.runtime

import java.io.File
import java.io.RandomAccessFile

/** Keep the on-screen error bounded even when a game leaves a very large Wine log. */
internal fun wineFailure(program: String, exitCode: Int, log: File): String {
    val summary = "$program завершился с кодом $exitCode."
    val lines = runCatching {
        RandomAccessFile(log, "r").use { file ->
            val count = file.length().coerceAtMost(16_384).toInt()
            val start = file.length() - count
            file.seek(start)
            val buffer = ByteArray(count).also(file::readFully)
            val text = buffer.toString(Charsets.UTF_8)
            (if (start == 0L) text else text.substringAfter('\n', ""))
                .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        }
    }.getOrDefault(emptyList())
    val kernelError = lines.lastOrNull { it.contains("could not load kernel32.dll", ignoreCase = true) }
    if (kernelError != null) {
        return "Wine не смог загрузить системную библиотеку kernel32.dll.\n$summary\n${kernelError.take(300)}"
    }
    val useful = lines.filterNot {
        it.contains("NTSync blocked by PROTON_NO_NTSYNC", ignoreCase = true) ||
            it.contains("client falls back to ESync", ignoreCase = true) ||
            it.contains("NTSync skipped, client uses ESync", ignoreCase = true)
    }
    val diagnostic = useful.lastOrNull { it.contains(":err:") || it.startsWith("wine:", ignoreCase = true) }
        ?: useful.lastOrNull()
    return if (diagnostic == null) summary else "$summary\n${diagnostic.take(360)}"
}
