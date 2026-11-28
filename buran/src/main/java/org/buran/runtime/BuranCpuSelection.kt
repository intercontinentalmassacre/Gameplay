package org.buran.runtime

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.RandomAccessFile

internal data class BuranCpuSelection(val fexSystem32: File? = null, val box64: File? = null) {
    companion object {
        const val EXTRA = "buranCpuPackages"

        fun resolve(context: Context, bundle: Bundle?): BuranCpuSelection {
            val contents = context.filesDir.resolve("contents").canonicalFile.toPath()
            fun locate(key: String): File? = bundle?.getString(key)?.let { path ->
                File(path).canonicalFile.takeIf {
                    it.toPath().startsWith(contents) && it.toPath().none { part -> ':' in part.toString() }
                }
            }
            return BuranCpuSelection(
                locate("fexSystem32")?.takeIf(WcpFiles::validFexDirectory),
                locate("box64")?.takeIf(::isGlibcBox64),
            )
        }

        internal fun isGlibcBox64(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { elf ->
                // ELF64 little-endian AArch64 executable with a glibc loader available in the guest.
                if (elf.readInt() != 0x7f454c46 || elf.readUnsignedByte() != 2 || elf.readUnsignedByte() != 1) return false
                fun shortLe() = java.lang.Short.reverseBytes(elf.readShort()).toInt() and 0xffff
                fun intLe() = Integer.reverseBytes(elf.readInt())
                fun longLe() = java.lang.Long.reverseBytes(elf.readLong())
                elf.seek(16)
                if (shortLe() !in 2..3 || shortLe() != 183) return false
                elf.seek(32)
                val headers = longLe()
                elf.seek(54)
                val entrySize = shortLe()
                val entryCount = shortLe()
                if (entrySize != 56 || entryCount !in 1..128 ||
                    headers !in 64..elf.length() - entrySize * entryCount) return false
                for (index in 0 until entryCount) {
                    val position = headers + index * entrySize
                    elf.seek(position)
                    if (intLe() != 3) continue // PT_INTERP
                    elf.seek(position + 8)
                    val offset = longLe()
                    elf.seek(position + 32)
                    val length = longLe()
                    if (length !in 1..160 || offset !in 0..elf.length() - length) return false
                    elf.seek(offset)
                    val path = ByteArray(length.toInt()).also(elf::readFully).toString(Charsets.US_ASCII)
                    return path == "/lib/ld-linux-aarch64.so.1\u0000" ||
                        path == "/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1\u0000"
                }
                false
            }
        }.getOrDefault(false)
    }
}
