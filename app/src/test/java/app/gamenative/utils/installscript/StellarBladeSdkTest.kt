package app.gamenative.utils.installscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StellarBladeSdkTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `only the known quiet PSPC setup is recovered from the nested script`() {
        val folder = temp.newFolder("SB", "Binaries", "Win64")
        val script = folder.resolve("installscript.vdf")
        val vendorScript = """
            "InstallScript" {
                "Run Process" {
                    "PSPC" { "process 1" "%INSTALLDIR%\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat" "command 1" "quiet" }
                    "Other" { "process 1" "%INSTALLDIR%\\tools\\other.bat" "command 1" "/S" }
                }
            }
        """.trimIndent()
        script.writeText(vendorScript)
        assertNull(InstallScriptExecutor.stellarBladeSdkScript(temp.root))
        folder.resolve("install_pspc_sdk_runtime.bat").writeText("@echo off")
        assertEquals("PSPC", InstallScriptExecutor.stellarBladeSdkScript(temp.root)?.runProcessActions?.single()?.name)
        script.writeText(vendorScript.replace("\"quiet\"", "\"quiet & unexpected\""))
        assertNull(InstallScriptExecutor.stellarBladeSdkScript(temp.root))
    }
}
