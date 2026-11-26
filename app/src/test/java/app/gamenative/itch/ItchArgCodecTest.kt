package app.gamenative.itch

import com.winlator.core.ProcessHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class ItchArgCodecTest {
    @Test
    fun `empty arguments produce no suffix`() {
        assertEquals("", ItchArgCodec.toWindowsCommandLine(emptyList()))
        assertEquals("", ItchArgCodec.encode(emptyList()))
        assertEquals(emptyList<String>(), ItchArgCodec.decode(""))
    }

    @Test
    fun `plain arguments stay unquoted`() {
        assertEquals(
            "--fullscreen --skip-intro",
            ItchArgCodec.toWindowsCommandLine(listOf("--fullscreen", "--skip-intro")),
        )
    }

    @Test
    fun `arguments with spaces are quoted and quotes escaped`() {
        assertEquals(
            "--save-dir \"My Saves\" \"--name=John \\\"J\\\"\"",
            ItchArgCodec.toWindowsCommandLine(listOf("--save-dir", "My Saves", "--name=John \"J\"")),
        )
    }

    @Test
    fun `manifest arguments survive native process tokenization`() {
        val args = listOf("", "--name=John \"J\"", "C:\\My Saves\\", "C:\\Games\\", "'player'", "--next")
        for (slashes in 0..4) {
            val withQuote = args + ("caption " + "\\".repeat(slashes) + "\"quoted\"")
            val command = ItchArgCodec.toWindowsCommandLine(withQuote)
            assertEquals(withQuote, ProcessHelper.splitCommand(command).toList())
        }
    }

    @Test
    fun `arguments survive the JSON round trip used by the container`() {
        val args = listOf("--save-dir", "My Saves", "-windowed", "")
        val encoded = ItchArgCodec.encode(args)
        assertEquals(args, ItchArgCodec.decode(encoded))
        assertEquals(listOf("--save-dir", "My Saves", "-windowed"), ItchArgCodec.decode(
            """["--save-dir","My Saves","-windowed"]""",
        ))
    }
}
