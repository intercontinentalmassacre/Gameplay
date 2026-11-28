package app.gamenative.utils

import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PulseAudioPayloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun reusesHealthyPayloadAndRepairsMissingOrChangedFiles() {
        val target = File(temporary.root, "pulseaudio")
        val pactl = File(target, "pactl")
        var version = "v1"
        var extractions = 0
        fun openArchive(): InputStream = version.byteInputStream()
        fun prepare(): Boolean = AssetUtils.preparePulseAudio(target, ::openArchive) { staging ->
            extractions++
            val paths = listOf(
                "modules/libprotocol-native.so",
                "modules/module-aaudio-sink.so",
                "modules/module-native-protocol-unix.so",
                "pactl",
            )
            for (path in paths) {
                File(staging, path).apply {
                    parentFile?.mkdirs()
                    writeText(version)
                    setExecutable(true)
                }
            }
            true
        }

        assertTrue(prepare())
        assertTrue(prepare())
        assertEquals(1, extractions)

        val originalTime = pactl.lastModified()
        pactl.writeText("XX")
        pactl.setLastModified(originalTime)
        assertTrue(prepare())
        assertEquals("v1", pactl.readText())

        assertTrue(pactl.delete())
        assertTrue(prepare())
        assertEquals("v1", pactl.readText())

        version = "v2"
        assertFalse(AssetUtils.preparePulseAudio(target, ::openArchive) { true })
        assertEquals("v1", pactl.readText())
        assertTrue(prepare())
        assertEquals("v2", pactl.readText())
        assertEquals(4, extractions)
    }
}
