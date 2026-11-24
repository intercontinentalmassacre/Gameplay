package com.winlator.container

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerOverlayFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(root: File, rel: String, content: String): File =
        File(root, rel).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun bionicContainer(rootDir: File): Container =
        Container("c1").apply {
            this.rootDir = rootDir
            containerVariant = Container.BIONIC
            wineVersion = "proton-9.0-arm64ec"
        }

    @Test
    fun migrationSharesOnlyIdenticalFilesAndPreservesRemovedFiles() {
        val base = tmp.newFolder("base")
        val container = bionicContainer(tmp.newFolder("game"))
        container.configFile.writeText("""{"extraData":{"custom":"kept"}}""")
        val upper = ContainerFiles.upperDir(container)
        val windows = "drive_c/windows/system32/"
        for (name in listOf("common.dll", "custom.dll", "removed.dll", "lsteamclient.dll")) {
            write(base, windows + name, "wine")
        }
        write(upper, windows + "common.dll", "wine")
        write(upper, windows + "custom.dll", "game")
        write(upper, windows + "lsteamclient.dll", "wine")
        write(upper, "user.reg", "saves")
        write(container.rootDir, ".cache/original_dlls/copy.dll", "backup")

        ContainerOverlayMigrator.migrate(container, base)
        ContainerOverlayMigrator.migrate(container, base)

        assertEquals(File(base, windows + "common.dll").canonicalFile, ContainerFiles.resolve(container, windows + "common.dll")?.canonicalFile)
        assertEquals("game", File(upper, windows + "custom.dll").readText())
        assertNull(ContainerFiles.resolve(container, windows + "removed.dll"))
        assertTrue(File(upper, windows + "lsteamclient.dll").isFile)
        assertEquals("saves", File(upper, "user.reg").readText())
        assertFalse(File(container.rootDir, ".cache/original_dlls").exists())
        val saved = org.json.JSONObject(container.configFile.readText()).getJSONObject("extraData")
        assertEquals("kept", saved.getString("custom"))
        assertFalse(saved.has("overlayMigrationPending"))
    }

    @Test
    fun deleteWithWhiteout_hidesBaseFileAndDropsUpperCopy() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/windows/system32/lsteamclient.dll", "base")
        write(upper, "drive_c/windows/system32/lsteamclient.dll", "upper")
        write(upper, "drive_c/windows/syswow64/only-upper.dll", "upper")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/system32/lsteamclient.dll"))
        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/syswow64/only-upper.dll"))
        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/syswow64/missing.dll"))

        assertFalse(exists(File(upper, "drive_c/windows/system32/lsteamclient.dll")))
        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/wh/drive_c/windows/system32/lsteamclient.dll").toPath()))
        assertNull(ContainerFiles.resolve(container, "drive_c/windows/system32/lsteamclient.dll"))
        assertFalse(exists(File(upper, "drive_c/windows/syswow64/only-upper.dll")))
        assertFalse(exists(File(upper, ".gnoverlay/wh/drive_c/windows/syswow64/only-upper.dll")))
        assertFalse(exists(File(upper, ".gnoverlay/wh/drive_c/windows/syswow64/missing.dll")))
        assertEquals("base", File(base, "drive_c/windows/system32/lsteamclient.dll").readText())
    }

    @Test
    fun deleteWithWhiteout_whitesOutDirectoriesPresentInBase() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/ProgramData/Game/base.txt", "b")
        write(upper, "drive_c/ProgramData/Game/cache.bin", "u")
        write(upper, ".gnoverlay/wh/drive_c/ProgramData/Game/old.txt", "")
        write(upper, ".gnoverlay/opaque/drive_c/ProgramData/Game/Sub", "")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/ProgramData/Game"))

        assertFalse(exists(File(upper, "drive_c/ProgramData/Game")))
        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/wh/drive_c/ProgramData/Game").toPath()))
        assertFalse(exists(File(upper, ".gnoverlay/opaque/drive_c/ProgramData/Game")))
        assertNull(ContainerFiles.resolve(container, "drive_c/ProgramData/Game/base.txt"))
    }

    @Test
    fun deleteWithWhiteout_isPlainDeleteForLegacyContainers() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        val upper = File(root, ".wine")
        write(base, "drive_c/windows/system32/a.dll", "b")
        write(upper, "drive_c/windows/system32/a.dll", "u")
        val container = bionicContainer(root).apply {
            basePrefix = base.absolutePath
            containerVariant = Container.GLIBC
        }

        assertTrue(ContainerFiles.deleteWithWhiteout(container, "drive_c/windows/system32/a.dll"))

        assertFalse(exists(File(upper, "drive_c/windows/system32/a.dll")))
        assertFalse(exists(File(upper, ".gnoverlay")))
    }

    @Test
    fun basePrefix_removeAppManagedFiles_stripsStagingBase() {
        val wine = tmp.newFolder("staging", ".wine")
        write(wine, "drive_c/windows/system32/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/syswow64/lsteamclient.dll", "x")
        write(wine, "drive_c/windows/system32/steam_api.dll", "keep")

        assertTrue(BasePrefix.removeAppManagedFiles(wine))

        assertFalse(exists(File(wine, "drive_c/windows/system32/lsteamclient.dll")))
        assertFalse(exists(File(wine, "drive_c/windows/syswow64/lsteamclient.dll")))
        assertTrue(File(wine, "drive_c/windows/system32/steam_api.dll").isFile)
    }

    @Test
    fun createThinPrefix_marksDosdevicesOpaque() {
        org.junit.Assume.assumeTrue("Wine drive names require a POSIX filesystem", File.separatorChar == '/')
        val base = tmp.newFolder("base", ".wine")
        Files.createDirectories(File(base, "dosdevices").toPath())
        Files.createSymbolicLink(File(base, "dosdevices/c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(base, "dosdevices/d:").toPath(), Paths.get("/sdcard/Download"))
        val upper = File(tmp.newFolder("container"), ".wine")

        assertTrue(ContainerOverlay.createThinPrefix(base, upper))
        Files.delete(File(upper, "dosdevices/d:").toPath())

        assertTrue(Files.isRegularFile(File(upper, ".gnoverlay/opaque/dosdevices").toPath()))
        assertNull(ContainerFiles.resolve(upper, base, "dosdevices/d:"))
        assertTrue(Files.isSymbolicLink(ContainerFiles.resolve(upper, base, "dosdevices/c:")!!.toPath()))
        assertEquals(File(upper, "dosdevices/c:"), ContainerFiles.resolve(upper, base, "dosdevices/c:"))
    }

    @Test
    fun basePrefix_normalizeKeepsOnlyCAndZDrives() {
        org.junit.Assume.assumeTrue("Wine drive names require a POSIX filesystem", File.separatorChar == '/')
        val wine = tmp.newFolder("base_prefix", ".wine")
        val dosdevices = File(wine, "dosdevices").apply { mkdirs() }
        Files.createSymbolicLink(File(dosdevices, "c:").toPath(), Paths.get("../drive_c"))
        Files.createSymbolicLink(File(dosdevices, "z:").toPath(), Paths.get("/"))
        Files.createSymbolicLink(File(dosdevices, "a:").toPath(), Paths.get("/storage/game"))
        Files.createSymbolicLink(File(dosdevices, "d:").toPath(), Paths.get("/sdcard/Download"))
        Files.createSymbolicLink(File(dosdevices, "e:").toPath(), Paths.get("/sdcard"))

        assertTrue(BasePrefix.normalize(wine))

        assertEquals(setOf("c:", "z:"), dosdevices.list()!!.toSet())
    }

    @Test
    fun createThinPrefix_copiesSkeletonFromBase() {
        val base = tmp.newFolder("base", ".wine")
        write(base, "system.reg", "sys")
        write(base, "user.reg", "usr")
        write(base, "userdef.reg", "def")
        write(base, "drive_c/users/xuser/Documents/readme.txt", "doc")
        write(base, "drive_c/windows/system32/kernel32.dll", "k32")
        Files.createDirectories(File(base, "dosdevices").toPath())
        write(base, "dosdevices/test-drive", "marker")
        val upper = File(tmp.newFolder("container"), ".wine")

        assertTrue(ContainerOverlay.createThinPrefix(base, upper))

        assertEquals("marker", File(upper, "dosdevices/test-drive").readText())
        assertEquals("sys", File(upper, "system.reg").readText())
        assertEquals("usr", File(upper, "user.reg").readText())
        assertEquals("def", File(upper, "userdef.reg").readText())
        assertEquals("doc", File(upper, "drive_c/users/xuser/Documents/readme.txt").readText())
        assertTrue(File(upper, "drive_c/windows/temp").isDirectory)
        assertTrue(File(upper, ".gnoverlay").isDirectory)
        assertFalse(exists(File(upper, "drive_c/windows/system32/kernel32.dll")))
    }

    @Test
    fun resolve_prefersUpperThenBaseUnlessWhitedOut() {
        val base = tmp.newFolder("base", ".wine")
        val upper = File(tmp.newFolder("container"), ".wine")
        write(base, "drive_c/windows/system32/a.dll", "base-a")
        write(base, "drive_c/windows/system32/b.dll", "base-b")
        write(upper, "drive_c/windows/system32/b.dll", "upper-b")
        write(base, "drive_c/windows/system32/c.dll", "base-c")
        write(upper, ".gnoverlay/wh/drive_c/windows/system32/c.dll", "")
        write(base, "drive_c/ProgramData/Dir/d.txt", "base-d")
        write(upper, ".gnoverlay/wh/drive_c/ProgramData/Dir", "")
        write(base, "drive_c/ProgramData/Opaque/e.txt", "base-e")
        write(upper, "drive_c/ProgramData/Opaque/f.txt", "upper-f")
        write(upper, ".gnoverlay/opaque/drive_c/ProgramData/Opaque", "")

        assertEquals("base-a", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/a.dll")!!.readText())
        assertEquals("upper-b", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/b.dll")!!.readText())
        assertEquals("base-a", ContainerFiles.resolve(upper, base, "drive_c\\windows\\system32\\a.dll")!!.readText())
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/windows/system32/c.dll"))
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Dir/d.txt"))
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Opaque/e.txt"))
        assertEquals("upper-f", ContainerFiles.resolve(upper, base, "drive_c/ProgramData/Opaque/f.txt")!!.readText())
        assertNull(ContainerFiles.resolve(upper, base, "drive_c/windows/system32/missing.dll"))
        assertNull(ContainerFiles.resolve(upper, null, "drive_c/windows/system32/a.dll"))
    }

    @Test
    fun resolve_usesBaseOnlyForOverlayContainers() {
        val base = tmp.newFolder("base", ".wine")
        val root = tmp.newFolder("container")
        write(base, "drive_c/windows/system32/a.dll", "base-a")
        val container = bionicContainer(root).apply { basePrefix = base.absolutePath }

        assertEquals("base-a", ContainerFiles.resolve(container, "drive_c/windows/system32/a.dll")!!.readText())
        container.containerVariant = Container.GLIBC
        assertNull(ContainerFiles.resolve(container, "drive_c/windows/system32/a.dll"))
    }

    @Test
    fun removeOverride_dropsUpperFileAndWhiteout() {
        val base = tmp.newFolder("base", ".wine")
        val upper = File(tmp.newFolder("container"), ".wine")
        write(base, "drive_c/windows/system32/d3d11.dll", "wine")
        write(upper, "drive_c/windows/system32/d3d11.dll", "dxvk")
        write(base, "drive_c/windows/syswow64/d3d11.dll", "wine32")
        write(upper, ".gnoverlay/wh/drive_c/windows/syswow64/d3d11.dll", "")

        assertTrue(ContainerFiles.removeOverride(upper, "drive_c/windows/system32/d3d11.dll"))
        assertTrue(ContainerFiles.removeOverride(upper, "drive_c/windows/syswow64/d3d11.dll"))

        assertEquals("wine", ContainerFiles.resolve(upper, base, "drive_c/windows/system32/d3d11.dll")!!.readText())
        assertEquals("wine32", ContainerFiles.resolve(upper, base, "drive_c/windows/syswow64/d3d11.dll")!!.readText())
    }

    @Test
    fun refreshSkeleton_keepsUserHive_backsUpTheOthers() {
        val base = tmp.newFolder("base2", ".wine")
        val upper = tmp.newFolder("container2", ".wine")
        write(base, "user.reg", "base-user")
        write(base, "system.reg", "base-system")
        write(upper, "user.reg", "game-saves")
        write(upper, "system.reg", "old-system")
        write(base, "userdef.reg", "base-userdef")
        write(upper, "userdef.reg", "old-userdef")

        ContainerOverlay.refreshSkeleton(upper, base)

        assertEquals("game-saves", File(upper, "user.reg").readText())
        assertFalse(exists(File(upper, "user.reg.prev")))
        assertEquals("base-system", File(upper, "system.reg").readText())
        assertEquals("old-system", File(upper, "system.reg.prev").readText())
        assertEquals("base-userdef", File(upper, "userdef.reg").readText())
        assertEquals("old-userdef", File(upper, "userdef.reg.prev").readText())
    }

    @Test
    fun refreshSkeleton_copiesUserHive_whenTheContainerHasNone() {
        val base = tmp.newFolder("base3", ".wine")
        val upper = tmp.newFolder("container3", ".wine")
        write(base, "user.reg", "base-user")

        ContainerOverlay.refreshSkeleton(upper, base)

        assertEquals("base-user", File(upper, "user.reg").readText())
        assertFalse(exists(File(upper, "user.reg.prev")))
    }
}
