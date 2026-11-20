package app.gamenative.localgames

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AppImageInspector {
    fun inspect(file: File): ExecutableInspection = RandomAccessFile(file, "r").use { input ->
        val header = ByteArray(64)
        if (input.length() < header.size) return invalid("The AppImage header is incomplete")
        input.readFully(header)
        val elf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (elf.getInt(0) != 0x464c457f || header[8] != 'A'.code.toByte() ||
            header[9] != 'I'.code.toByte() || header[10] != 2.toByte()
        ) return invalid("Choose a Type 2 Linux AppImage")
        if (header[4] != 2.toByte() || header[5] != 1.toByte() || elf.getShort(18).toInt() != 183) {
            return invalid("Choose an ARM64 (aarch64) AppImage for this device")
        }

        // Type 2 places SquashFS after both the section table and the last ELF section.
        val sections = elf.getLong(40)
        val entrySize = elf.getShort(58).toInt() and 0xffff
        val count = elf.getShort(60).toInt() and 0xffff
        val tableSize = entrySize.toLong() * count
        if (sections < header.size || entrySize < 64 || count == 0 ||
            sections > input.length() - tableSize - 96
        ) return invalid("The AppImage filesystem is missing or incomplete")
        input.seek(sections + tableSize - entrySize + 24)
        val lastOffset = java.lang.Long.reverseBytes(input.readLong())
        val lastSize = java.lang.Long.reverseBytes(input.readLong())
        if (lastOffset < 0 || lastSize < 0 || lastOffset > input.length() - 96 - lastSize) {
            return invalid("The AppImage section extends beyond the file")
        }
        val filesystem = maxOf(sections + tableSize, lastOffset + lastSize)
        input.seek(filesystem)
        if (input.readInt() != 0x68737173) return invalid("The AppImage has no SquashFS filesystem")
        input.seek(filesystem + 40) // SquashFS superblock bytes_used
        val bytesUsed = java.lang.Long.reverseBytes(input.readLong())
        if (bytesUsed !in 96L..(input.length() - filesystem)) return invalid("The AppImage filesystem is incomplete")
        ExecutableInspection(ExecutableKind.ARM64_APPIMAGE)
    }

    private fun invalid(reason: String) = ExecutableInspection(ExecutableKind.MALFORMED, reason)
}
