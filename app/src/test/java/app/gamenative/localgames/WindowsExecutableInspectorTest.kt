package app.gamenative.localgames

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsExecutableInspectorTest {
    @Test
    fun `identifies a win16 NE executable`() {
        assertEquals(
            ExecutableKind.WINDOWS_16_NE,
            inspect(peHeader = byteArrayOf(0x4e, 0x45, 0, 0)),
        )
    }

    @Test
    fun `identifies a 32-bit PE executable`() {
        assertEquals(ExecutableKind.WINDOWS_32_PE, inspect(peHeader = peHeader(optionalHeaderMagic = 0x10b)))
    }

    @Test
    fun `identifies a 64-bit PE executable`() {
        assertEquals(ExecutableKind.WINDOWS_64_PE, inspect(peHeader = peHeader(optionalHeaderMagic = 0x20b)))
    }

    @Test
    fun `identifies an MSI compound document`() {
        val msi = byteArrayOf(
            0xd0.toByte(),
            0xcf.toByte(),
            0x11,
            0xe0.toByte(),
            0xa1.toByte(),
            0xb1.toByte(),
            0x1a,
            0xe1.toByte(),
        )
        assertEquals(
            ExecutableKind.WINDOWS_INSTALLER_MSI,
            WindowsExecutableInspector.inspect(ByteArrayInputStream(msi)).kind,
        )
    }

    @Test
    fun `treats MZ files without a Windows header as DOS only`() {
        val dos = ByteArray(64)
        dos[0] = 0x4d
        dos[1] = 0x5a
        assertEquals(
            ExecutableKind.DOS_ONLY,
            WindowsExecutableInspector.inspect(ByteArrayInputStream(dos)).kind,
        )
    }

    @Test
    fun `rejects an unsafe new header offset`() {
        val dos = ByteArray(64)
        dos[0] = 0x4d
        dos[1] = 0x5a
        dos.writeIntLittleEndian(0x3c, 2 * 1024 * 1024)
        assertEquals(
            ExecutableKind.MALFORMED,
            WindowsExecutableInspector.inspect(ByteArrayInputStream(dos)).kind,
        )
    }

    private fun inspect(peHeader: ByteArray): ExecutableKind {
        val dos = ByteArray(64)
        dos[0] = 0x4d
        dos[1] = 0x5a
        dos.writeIntLittleEndian(0x3c, 64)
        val executable = ByteArray(dos.size + peHeader.size)
        System.arraycopy(dos, 0, executable, 0, dos.size)
        System.arraycopy(peHeader, 0, executable, dos.size, peHeader.size)
        return WindowsExecutableInspector.inspect(ByteArrayInputStream(executable)).kind
    }

    private fun peHeader(optionalHeaderMagic: Int): ByteArray =
        ByteArray(26).also {
            it[0] = 0x50
            it[1] = 0x45
            it[24] = (optionalHeaderMagic and 0xff).toByte()
            it[25] = ((optionalHeaderMagic shr 8) and 0xff).toByte()
        }

    private fun ByteArray.writeIntLittleEndian(offset: Int, value: Int) {
        this[offset] = (value and 0xff).toByte()
        this[offset + 1] = ((value shr 8) and 0xff).toByte()
        this[offset + 2] = ((value shr 16) and 0xff).toByte()
        this[offset + 3] = ((value shr 24) and 0xff).toByte()
    }
}
