package app.gamenative.service

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDigestTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun matchesStoreHashesAcrossBufferBoundaries() {
        val file = temp.newFile()
        for (size in listOf(0, DEFAULT_BUFFER_SIZE, DEFAULT_BUFFER_SIZE * 3 + 7)) {
            val bytes = ByteArray(size) { (it * 31).toByte() }
            file.writeBytes(bytes)
            for (algorithm in listOf("MD5", "SHA-1")) {
                assertArrayEquals(MessageDigest.getInstance(algorithm).digest(bytes), file.computeDigest(algorithm))
            }
        }
    }
}
