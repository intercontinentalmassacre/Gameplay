package app.gamenative.localgames

import java.io.IOException
import java.io.InputStream

/**
 * Inspects the small, untrusted header portion of a Windows executable or MSI package.
 *
 * No file is executed, extracted, or copied here. Callers should inspect before allocating a
 * container or accepting a lengthy import operation.
 */
object WindowsExecutableInspector {
    private const val DOS_HEADER_SIZE = 64
    private const val NEW_HEADER_OFFSET = 0x3c
    private const val MAX_NEW_HEADER_OFFSET = 1024 * 1024
    private const val DOS_MAGIC_M = 0x4d
    private const val DOS_MAGIC_Z = 0x5a
    private const val NE_MAGIC_N = 0x4e
    private const val NE_MAGIC_E = 0x45
    private const val PE_MAGIC_P = 0x50
    private const val PE_MAGIC_E = 0x45

    private val msiCompoundFileSignature = byteArrayOf(
        0xd0.toByte(),
        0xcf.toByte(),
        0x11,
        0xe0.toByte(),
        0xa1.toByte(),
        0xb1.toByte(),
        0x1a,
        0xe1.toByte(),
    )

    fun inspect(input: InputStream): ExecutableInspection {
        return try {
            val leadingBytes = input.readExactlyOrNull(msiCompoundFileSignature.size)
                ?: return ExecutableInspection(ExecutableKind.UNKNOWN, "File is too small to identify")

            if (leadingBytes.startsWith(msiCompoundFileSignature)) {
                ExecutableInspection(ExecutableKind.WINDOWS_INSTALLER_MSI)
            } else {
                val remainingDosHeader = input.readExactlyOrNull(DOS_HEADER_SIZE - leadingBytes.size)
                    ?: return ExecutableInspection(ExecutableKind.UNKNOWN, "File is too small to identify")
                val header = ByteArray(DOS_HEADER_SIZE)
                System.arraycopy(leadingBytes, 0, header, 0, leadingBytes.size)
                System.arraycopy(remainingDosHeader, 0, header, leadingBytes.size, remainingDosHeader.size)

                if (!header.startsWith(byteArrayOf(DOS_MAGIC_M.toByte(), DOS_MAGIC_Z.toByte()))) {
                    ExecutableInspection(ExecutableKind.UNKNOWN, "The file is not a Windows executable or MSI package")
                } else {
                    inspectDosExecutable(input, header)
                }
            }
        } catch (exception: IOException) {
            ExecutableInspection(ExecutableKind.MALFORMED, "Unable to read file header: ${exception.message}")
        }
    }

    private fun inspectDosExecutable(input: InputStream, dosHeader: ByteArray): ExecutableInspection {
        val newHeaderOffset = dosHeader.readUnsignedIntLittleEndian(NEW_HEADER_OFFSET)
        if (newHeaderOffset == 0L) {
            return ExecutableInspection(ExecutableKind.DOS_ONLY)
        }
        if (newHeaderOffset > MAX_NEW_HEADER_OFFSET) {
            return ExecutableInspection(
                ExecutableKind.MALFORMED,
                "Executable header offset is outside the supported inspection limit",
            )
        }
        if (newHeaderOffset < DOS_HEADER_SIZE) {
            return ExecutableInspection(ExecutableKind.MALFORMED, "Executable header overlaps the DOS header")
        }

        if (!input.skipExactly(newHeaderOffset - DOS_HEADER_SIZE)) {
            return ExecutableInspection(ExecutableKind.DOS_ONLY)
        }

        return when (val signature = input.readExactlyOrNull(4)) {
            null -> ExecutableInspection(ExecutableKind.DOS_ONLY)
            else -> when {
                signature[0] == NE_MAGIC_N.toByte() && signature[1] == NE_MAGIC_E.toByte() ->
                    ExecutableInspection(ExecutableKind.WINDOWS_16_NE)
                signature.startsWith(byteArrayOf(PE_MAGIC_P.toByte(), PE_MAGIC_E.toByte(), 0, 0)) ->
                    inspectPortableExecutable(input)
                else -> ExecutableInspection(
                    ExecutableKind.DOS_ONLY,
                    "DOS executable has no Windows NE or PE header",
                )
            }
        }
    }

    private fun inspectPortableExecutable(input: InputStream): ExecutableInspection {
        // COFF file header is 20 bytes; the optional-header magic follows it.
        input.readExactlyOrNull(20)
            ?: return ExecutableInspection(ExecutableKind.MALFORMED, "PE file header is truncated")
        val optionalHeaderMagic = input.readExactlyOrNull(2)
            ?: return ExecutableInspection(ExecutableKind.MALFORMED, "PE optional header is truncated")

        return when (optionalHeaderMagic.readUnsignedShortLittleEndian(0)) {
            0x10b -> ExecutableInspection(ExecutableKind.WINDOWS_32_PE)
            0x20b -> ExecutableInspection(ExecutableKind.WINDOWS_64_PE)
            else -> ExecutableInspection(ExecutableKind.MALFORMED, "Unknown PE optional-header format")
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false

        var index = 0
        while (index < prefix.size) {
            if (this[index] != prefix[index]) return false
            index++
        }
        return true
    }

    private fun ByteArray.readUnsignedIntLittleEndian(offset: Int): Long =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun InputStream.readExactlyOrNull(length: Int): ByteArray? {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            when {
                count > 0 -> offset += count
                count == 0 -> return null
                else -> return null
            }
        }
        return result
    }

    private fun InputStream.skipExactly(length: Long): Boolean {
        var remaining = length
        while (remaining > 0) {
            val skipped = skip(remaining)
            when {
                skipped > 0 -> remaining -= skipped
                read() == -1 -> return false
                else -> remaining--
            }
        }
        return true
    }
}
