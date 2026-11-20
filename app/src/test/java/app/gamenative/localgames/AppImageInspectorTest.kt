package app.gamenative.localgames

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppImageInspectorTest {
    @get:Rule val files = TemporaryFolder()

    private fun image(lastSectionEnd: Int = 0): ByteBuffer {
        val bytes = ByteBuffer.allocate(maxOf(128, lastSectionEnd) + 96).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0, 0x464c457f)
        bytes.put(4, 2).put(5, 1).put(8, 65).put(9, 73).put(10, 2)
        bytes.putShort(18, 183).putLong(40, 64).putShort(58, 64).putShort(60, 1)
        bytes.putLong(64 + 24, 0).putLong(64 + 32, lastSectionEnd.toLong())
        bytes.putInt(maxOf(128, lastSectionEnd), 0x73717368)
        bytes.putLong(maxOf(128, lastSectionEnd) + 40, 96)
        return bytes
    }

    private fun inspect(bytes: ByteArray): ExecutableKind {
        val file = files.newFile()
        file.writeBytes(bytes)
        return AppImageInspector.inspect(file).kind
    }

    @Test fun acceptsSquashfsAfterEitherEndOfElf() {
        assertEquals(ExecutableKind.ARM64_APPIMAGE, inspect(image().array()))
        assertEquals(ExecutableKind.ARM64_APPIMAGE, inspect(image(256).array()))
    }

    @Test fun rejectsUnsupportedOrIncompleteImages() {
        for (invalid in listOf(
            image().put(10, 1).array(),
            image().putShort(18, 62).array(),
            image().putLong(40, Long.MAX_VALUE).array(),
            image().putLong(64 + 32, Long.MAX_VALUE).array(),
            image().putInt(128, 0).array(),
            image().putLong(128 + 40, 97).array(),
            image().array().copyOf(30),
        )) assertEquals(ExecutableKind.MALFORMED, inspect(invalid))
    }
}
