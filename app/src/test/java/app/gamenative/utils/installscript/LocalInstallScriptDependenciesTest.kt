package app.gamenative.utils.installscript

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalInstallScriptDependenciesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun resolvesBatmanStylePhysXRedistributableFromInstallScript() {
        val game = temp.newFolder("Batman Arkham Asylum GOTY")
        val installer = file(game, "redist/PhysX_9.08.14_9.09.0814_SystemSoftware.exe")
        val script = InstallScriptParser.parseFromString(
            """
            "InstallScript" {
                "Run Process" {
                    "PhysX" {
                        "Process 1" "%INSTALLDIR%\\REDIST\\PhysX_9.08.14_9.09.0814_SystemSoftware.exe"
                        "Command 1" "/quiet /norestart"
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\35140\\PhysX"
                        "MinimumHasRunValue" "2"
                    }
                }
            }
            """.trimIndent(),
            "A:",
        )

        val result = InstallScriptExecutor.localDependencies(listOf(script), game, null, is64Bit = true).single()

        assertEquals(installer, result.executable)
        assertEquals(listOf("/quiet", "/norestart"), result.arguments)
        assertEquals("HKLM\\Software\\Valve\\Steam\\Apps\\35140\\PhysX\\PhysX", result.marker)
        assertEquals(2, result.markerValue)
    }

    @Test
    fun resolvesAnotherTitlesMsiWithItsOwnOsRequirementAndUserMarker() {
        val game = temp.newFolder("Another title")
        val installer = file(game, "support/Audio Tools.msi")
        val action = RunProcessAction(
            name = "Audio Runtime",
            process = "A:/support/Audio Tools.msi",
            command = "/quiet INSTALLDIR=\"C:\\Audio Tools\"",
            hasRunKey = "HKEY_CURRENT_USER\\Software\\OtherTitle\\AudioTools",
            requirementOS = OSRequirement(is64BitWindows = true),
        )

        val result = resolve(game, listOf(action)).single()

        assertEquals(installer, result.executable)
        assertEquals(listOf("/quiet", "INSTALLDIR=C:\\Audio Tools"), result.arguments)
        assertEquals("HKCU\\Software\\OtherTitle\\AudioTools\\Audio Runtime", result.marker)
        assertEquals(1, result.markerValue)
        assertTrue(resolve(game, listOf(action), is64Bit = false).isEmpty())
    }

    @Test
    fun omitsUninstallEntriesByActionNameFileNameAndCommand() {
        val game = temp.newFolder("Title")
        val install = file(game, "setup.exe")
        file(game, "unins000.exe")
        file(game, "runtime.msi")
        val actions = listOf(
            RunProcessAction("Uninstall runtime", "A:/setup.exe", "/quiet"),
            RunProcessAction("Cleanup", "A:/unins000.exe", "/S"),
            RunProcessAction("Maintain runtime", "A:/runtime.msi", "/x{12345678-1234-1234-1234-123456789012}"),
            RunProcessAction("Install runtime", "A:/setup.exe", "/quiet"),
        )

        assertEquals(listOf(install), resolve(game, actions).map { it.executable })
    }

    @Test
    fun usesSharedRedistributableOnlyWhenTheLocalSharedRootIsAvailable() {
        val game = temp.newFolder("Title")
        val shared = temp.newFolder("Steamworks Shared")
        val installer = file(shared, "_CommonRedist/vcredist/2022/vc_redist.x86.exe")
        val action = RunProcessAction(
            "Visual C++",
            "C:/Program Files (x86)/Steam/steamapps/common/Steamworks Shared/_CommonRedist/vcredist/2022/vc_redist.x86.exe",
            "/install /quiet /norestart",
        )

        assertEquals(installer, resolve(game, listOf(action), shared).single().executable)
        assertTrue(resolve(game, listOf(action)).isEmpty())
    }

    @Test
    fun sharedHasRunKeyKeepsSeparateX86AndX64ActionValues() {
        val game = temp.newFolder("Title")
        val x86 = file(game, "redist/vcredist_x86.exe")
        val x64 = file(game, "redist/vcredist_x64.exe")
        val key = "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\Example\\VC"
        val actions = listOf(
            RunProcessAction("x86 SP1", "A:/redist/vcredist_x86.exe", hasRunKey = key),
            RunProcessAction("x64 SP1", "A:/redist/vcredist_x64.exe", hasRunKey = key),
        )

        val result = resolve(game, actions)

        assertEquals(listOf(x86, x64), result.map { it.executable })
        assertEquals(
            listOf(
                "HKLM\\Software\\Valve\\Steam\\Apps\\Example\\VC\\x86 SP1",
                "HKLM\\Software\\Valve\\Steam\\Apps\\Example\\VC\\x64 SP1",
            ),
            result.map { it.marker },
        )
    }

    @Test
    fun x86AndX64ActionsCanShareOneRegistryKey() {
        val game = temp.newFolder("Multi architecture")
        file(game, "vcredist_x86.exe")
        file(game, "vcredist_x64.exe")
        val key = "HKLM\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2008"
        val actions = listOf(
            RunProcessAction("VC 2008 x86", "A:/vcredist_x86.exe", hasRunKey = key),
            RunProcessAction("VC 2008 x64", "A:/vcredist_x64.exe", hasRunKey = key),
        )

        assertEquals(listOf("$key\\VC 2008 x86", "$key\\VC 2008 x64"), resolve(game, actions).map { it.marker })
    }

    @Test
    fun rejectsTraversalEvenWhenTheExternalInstallerExists() {
        val game = temp.newFolder("Title")
        val shared = temp.newFolder("Steamworks Shared")
        file(temp.root, "outside.exe")
        file(game, "setup.exe")
        val actions = listOf(
            RunProcessAction("Game parent", "A:/../outside.exe"),
            RunProcessAction("Relative parent", "../outside.exe"),
            RunProcessAction("Shared parent", "C:/Program Files (x86)/Steam/steamapps/common/Steamworks Shared/../outside.exe"),
            RunProcessAction("Absolute outside root", "D:/outside.exe"),
            RunProcessAction("Directory traversal inside root", "A:/../Title/setup.exe"),
        )

        assertTrue(resolve(game, actions, shared).isEmpty())
    }

    private fun resolve(
        game: File,
        actions: List<RunProcessAction>,
        shared: File? = null,
        is64Bit: Boolean = true,
    ) = InstallScriptExecutor.localDependencies(
        listOf(InstallScript("installscript.vdf", runProcessActions = actions)),
        game, shared, is64Bit,
    )

    private fun file(root: File, relative: String): File = root.resolve(relative).apply {
        parentFile.mkdirs()
        writeText("test fixture")
    }.canonicalFile
}
