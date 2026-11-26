package app.gamenative.utils.protonFixes

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.WineInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class CommonControlsV6Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private val assembly = "microsoft.windows.common-controls_6595b64144ccf1df_6.0.2600.2982_none_deadbeef"
    private val context = mockk<Context>(relaxed = true)

    /** A wine tree with `lib/wine/<peDir>/<file>` holding [content] for every entry. */
    private fun wineTree(name: String, files: Map<String, Map<String, String>>): File {
        val root = tmp.newFolder(name)
        for ((peDir, dlls) in files) {
            val dir = File(root, "lib/wine/$peDir").apply { mkdirs() }
            for ((dll, content) in dlls) File(dir, dll).writeText(content)
        }
        return root
    }

    private fun container(root: File, basePrefix: String = ""): Container {
        val container = mockk<Container>(relaxed = true)
        every { container.rootDir } returns root
        every { container.basePrefix } returns basePrefix
        every { container.isOverlay } returns false
        return container
    }

    /** A real container, so isOverlay() really is ContainerOverlay.isEligible plus a basePrefix. */
    private fun overlayContainer(root: File, base: File): Container =
        Container("c1").apply {
            this.rootDir = root
            containerVariant = Container.BIONIC
            wineVersion = "proton-9.0-arm64ec"
            basePrefix = base.absolutePath
        }

    private fun winsxs(wine: File) = File(wine, "drive_c/windows/winsxs")
    private fun assemblyDll(winsxs: File, arch: String) = File(winsxs, "${arch}_$assembly/comctl32.dll")
    private fun manifest(winsxs: File, arch: String) = File(winsxs, "manifests/${arch}_$assembly.manifest")

    /** What setupapi leaves behind: a copy of the DLL and Wine's own manifest. */
    private fun seedAssembly(winsxs: File, arch: String, content: String) {
        assemblyDll(winsxs, arch).apply { parentFile!!.mkdirs() }.writeText(content)
        manifest(winsxs, arch).apply { parentFile!!.mkdirs() }.writeText(
            "<assembly><assemblyIdentity name=\"Microsoft.Windows.Common-Controls\" " +
                "processorArchitecture=\"$arch\"/><file name=\"comctl32.dll\"><windowClass>Combobox</windowClass></file></assembly>",
        )
    }

    private fun assertServes(winsxs: File, arch: String, content: String) {
        assertEquals(content, assemblyDll(winsxs, arch).readText())
        assertTrue(manifest(winsxs, arch).readText().contains("processorArchitecture=\"$arch\""))
    }

    private val preSplitArm64ec = mapOf(
        "aarch64-windows" to mapOf("comctl32.dll" to "pre-split arm64"),
        "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
    )

    private val splitArm64ec = mapOf(
        "aarch64-windows" to mapOf("comctl32.dll" to "v5 arm64", "comctl32_v6.dll" to "v6 arm64"),
        "i386-windows" to mapOf("comctl32.dll" to "v5 x86", "comctl32_v6.dll" to "v6 x86"),
    )

    private val preSplitX8664 = mapOf(
        "x86_64-windows" to mapOf("comctl32.dll" to "pre-split amd64"),
        "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
    )

    @Test
    fun `a build without comctl32_v6 keeps its own assembly and manifest`() {
        val tree = wineTree("proton-10.0-arm64ec", preSplitArm64ec)
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "pre-split arm64")
        seedAssembly(sxs, "x86", "pre-split x86")

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path))

        // Only the amd64_ name, which arm64ec always gets, is new; Wine's two copies are left as they are.
        assertEquals(1, installed)
        val arm64Src = File(tree, "lib/wine/aarch64-windows/comctl32.dll")
        assertFalse(Files.isSameFile(assemblyDll(sxs, "arm64").toPath(), arm64Src.toPath()))
        assertServes(sxs, "arm64", "pre-split arm64")
        assertServes(sxs, "x86", "pre-split x86")
        assertServes(sxs, "amd64", "pre-split arm64")
        assertEquals(0, CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path)))
    }

    @Test
    fun `a prefix that lost the assembly is repaired from the build's comctl32`() {
        val tree = wineTree(
            "proton-9.0-x86_64",
            mapOf(
                "x86_64-windows" to mapOf("comctl32.dll" to "pre-split amd64"),
                "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        File(sxs, "manifests").mkdirs()

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "9.0", "x86_64", tree.path))

        assertEquals(2, installed)
        assertServes(sxs, "amd64", "pre-split amd64")
        assertServes(sxs, "x86", "pre-split x86")
    }

    @Test
    fun `an assembly left by another build is replaced with this build's DLL`() {
        val tree = wineTree("proton-10.0-arm64ec", preSplitArm64ec)
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "amd64", "proton 11 v6 arm64")
        seedAssembly(sxs, "arm64", "proton 11 v6 arm64")
        seedAssembly(sxs, "x86", "proton 11 v6 x86")

        val installed = CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "10.0", "arm64ec", tree.path))

        assertEquals(3, installed)
        assertServes(sxs, "amd64", "pre-split arm64")
        assertServes(sxs, "arm64", "pre-split arm64")
        assertServes(sxs, "x86", "pre-split x86")
    }

    @Test
    fun `an arch the build has no PE directory for is still removed`() {
        val tree = wineTree(
            "proton-9.0-x86_64",
            mapOf(
                "x86_64-windows" to mapOf("comctl32.dll" to "pre-split amd64"),
                "i386-windows" to mapOf("comctl32.dll" to "pre-split x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "proton 11 v6 arm64")

        CommonControlsV6.ensureInstalled(context, container(root), WineInfo("proton", "9.0", "x86_64", tree.path))

        assertFalse(File(sxs, "arm64_$assembly").exists())
        assertFalse(manifest(sxs, "arm64").exists())
        assertServes(sxs, "amd64", "pre-split amd64")
    }

    @Test
    fun `a missing Wine tree leaves an existing assembly intact`() {
        val wine = WineInfo("proton", "10.0", "arm64ec", File(tmp.root, "missing").path)
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "previous arm64")

        assertEquals(0, CommonControlsV6.ensureInstalled(context, container(root), wine))
        assertServes(sxs, "arm64", "previous arm64")
    }

    @Test
    fun `a split build installs comctl32_v6 and never its v5 comctl32`() {
        val tree = wineTree(
            "proton-11.0-arm64ec",
            mapOf(
                "aarch64-windows" to mapOf("comctl32.dll" to "v5 arm64", "comctl32_v6.dll" to "v6 arm64"),
                "i386-windows" to mapOf("comctl32.dll" to "v5 x86", "comctl32_v6.dll" to "v6 x86"),
            ),
        )
        val root = tmp.newFolder("container")
        val sxs = winsxs(File(root, ".wine"))
        seedAssembly(sxs, "arm64", "pre-split arm64")

        val wine = WineInfo("proton", "11.0", "arm64ec", tree.path)
        assertEquals(3, CommonControlsV6.ensureInstalled(context, container(root), wine))
        assertServes(sxs, "amd64", "v6 arm64")
        assertServes(sxs, "arm64", "v6 arm64")
        assertServes(sxs, "x86", "v6 x86")
        assertEquals(0, CommonControlsV6.ensureInstalled(context, container(root), wine))

        // A split build missing one arch's v6 DLL drops that arch instead of serving the v5 DLL.
        File(tree, "lib/wine/i386-windows/comctl32_v6.dll").delete()
        CommonControlsV6.ensureInstalled(context, container(root), wine)
        assertFalse(File(sxs, "x86_$assembly").exists())
        assertFalse(manifest(sxs, "x86").exists())
        assertArrayEquals("v6 arm64".toByteArray(), assemblyDll(sxs, "arm64").readBytes())
    }

    @Test
    fun `an overlay installs into the shared base prefix and prunes a stale local copy`() {
        val tree = wineTree("proton-11.0-overlay", splitArm64ec)
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val baseSxs = winsxs(base)
        val upperSxs = winsxs(File(root, ".wine"))
        seedAssembly(baseSxs, "amd64", "old base assembly")
        // The container pattern carries an x86_64 v6 DLL under amd64_, which an arm64ec process
        // cannot load and which wins the overlay lookup over the base prefix.
        seedAssembly(upperSxs, "amd64", "container pattern x86_64 v6")

        val container = overlayContainer(root, base)
        val wine = WineInfo("proton", "11.0", "arm64ec", tree.path)
        assertEquals(3, CommonControlsV6.ensureInstalled(context, container, wine))

        assertServes(baseSxs, "amd64", "v6 arm64")
        assertServes(baseSxs, "arm64", "v6 arm64")
        assertServes(baseSxs, "x86", "v6 x86")
        assertFalse(File(upperSxs, "amd64_$assembly").exists())
        assertFalse(manifest(upperSxs, "amd64").exists())
        // The container's own layer is not a second install target.
        assertFalse(File(upperSxs, "arm64_$assembly").exists())
        assertFalse(File(upperSxs, "x86_$assembly").exists())
        assertEquals(0, CommonControlsV6.ensureInstalled(context, container, wine))
    }

    @Test
    fun `a current copy in the container's own layer is kept and its manifest repaired`() {
        val tree = wineTree("proton-11.0-overlay-kept", splitArm64ec)
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upperSxs = winsxs(File(root, ".wine"))
        // A full prefix that already ran this installer keeps this build's DLL in its own layer.
        seedAssembly(upperSxs, "amd64", "v6 arm64")

        CommonControlsV6.ensureInstalled(
            context, overlayContainer(root, base), WineInfo("proton", "11.0", "arm64ec", tree.path),
        )

        assertTrue(assemblyDll(upperSxs, "amd64").isFile)
        // The local manifest has to agree with the local DLL, or it shadows the base pair.
        assertTrue(manifest(upperSxs, "amd64").readText().contains("publicKeyToken"))
        assertServes(winsxs(base), "amd64", "v6 arm64")
    }

    @Test
    fun `a leftover temp file in the container's own layer is pruned with its manifest`() {
        val tree = wineTree("proton-11.0-overlay-tmp", splitArm64ec)
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upperSxs = winsxs(File(root, ".wine"))
        val dir = File(upperSxs, "amd64_$assembly").apply { mkdirs() }
        File(dir, "comctl32.dll.gnnew").writeText("interrupted")
        manifest(upperSxs, "amd64").apply { parentFile!!.mkdirs() }.writeText("<stale/>")

        CommonControlsV6.ensureInstalled(
            context, overlayContainer(root, base), WineInfo("proton", "11.0", "arm64ec", tree.path),
        )

        assertFalse(dir.exists())
        assertFalse(manifest(upperSxs, "amd64").exists())
        assertServes(winsxs(base), "amd64", "v6 arm64")
    }

    @Test
    fun `an arch this build cannot serve is pruned in both layers`() {
        val tree = wineTree(
            "proton-11.0-overlay-no-x86-v6",
            mapOf(
                "aarch64-windows" to mapOf("comctl32.dll" to "v5 arm64", "comctl32_v6.dll" to "v6 arm64"),
                "i386-windows" to mapOf("comctl32.dll" to "v5 x86"),
            ),
        )
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val baseSxs = winsxs(base)
        val upperSxs = winsxs(File(root, ".wine"))
        seedAssembly(baseSxs, "x86", "old v6 x86")
        seedAssembly(upperSxs, "x86", "old v6 x86")

        CommonControlsV6.ensureInstalled(
            context, overlayContainer(root, base), WineInfo("proton", "11.0", "arm64ec", tree.path),
        )

        assertFalse(File(baseSxs, "x86_$assembly").exists())
        assertFalse(manifest(baseSxs, "x86").exists())
        assertFalse(File(upperSxs, "x86_$assembly").exists())
        assertFalse(manifest(upperSxs, "x86").exists())
        assertServes(baseSxs, "arm64", "v6 arm64")
    }

    @Test
    fun `an overlay whose base prefix is gone is left alone`() {
        val tree = wineTree("proton-11.0-overlay-missing-base", splitArm64ec)
        val root = tmp.newFolder("container")
        val upperSxs = winsxs(File(root, ".wine"))
        seedAssembly(upperSxs, "amd64", "previous")
        val base = File(File(tmp.root, "missing-base"), ".wine")

        assertEquals(
            0,
            CommonControlsV6.ensureInstalled(
                context, overlayContainer(root, base), WineInfo("proton", "11.0", "arm64ec", tree.path),
            ),
        )

        assertFalse(File(tmp.root, "missing-base").exists())
        assertServes(upperSxs, "amd64", "previous")
    }

    @Test
    fun `a container that is not an overlay keeps using its own prefix`() {
        val tree = wineTree("proton-9.0-not-overlay", preSplitX8664)
        val root = tmp.newFolder("container")
        val unusedBase = File(File(tmp.root, "unused-base"), ".wine").absolutePath
        val target = container(root, unusedBase)

        assertEquals(
            2,
            CommonControlsV6.ensureInstalled(context, target, WineInfo("proton", "9.0", "x86_64", tree.path)),
        )

        assertServes(winsxs(File(root, ".wine")), "amd64", "pre-split amd64")
        assertFalse(File(tmp.root, "unused-base").exists())
    }
}
