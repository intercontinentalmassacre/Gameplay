package app.gamenative.localgames

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuranPrivatePrefixFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun seedAndRepairIgnoreForeignDriveLinksAndKeepPrivateSaves() {
        assumeTrue("Wine drive names require a POSIX filesystem", File.separatorChar == '/')
        val foreign = tmp.newFolder("foreign-prefix")
        val foreignDrive = File(foreign, "drive_c").apply { mkdir() }
        File(foreignDrive, "sentinel").writeText("foreign")
        val source = tmp.newFolder("template")
        val sourceDrives = File(source, "dosdevices").apply { mkdir() }
        Files.createSymbolicLink(File(sourceDrives, "c:").toPath(), foreignDrive.toPath())
        Files.createSymbolicLink(File(sourceDrives, "z:").toPath(), foreign.toPath())
        val sourceDrive = File(source, "drive_c").apply { mkdir() }
        File(sourceDrive, "settings.ini").writeText("seed")
        Files.createSymbolicLink(File(sourceDrive, "shared").toPath(), foreignDrive.toPath())
        Files.createSymbolicLink(File(sourceDrive, "settings-link").toPath(), Paths.get("settings.ini"))

        val prefix = tmp.newFolder("private")
        val privateDrive = File(prefix, "drive_c").apply { mkdir() }
        val save = File(privateDrive, "settings.ini").apply { writeText("private save") }
        val existingDrives = File(prefix, "dosdevices").apply { mkdir() }
        Files.createSymbolicLink(File(existingDrives, "c:").toPath(), foreignDrive.toPath())
        Files.createSymbolicLink(File(existingDrives, "d:").toPath(), foreignDrive.toPath())
        Files.createSymbolicLink(File(existingDrives, "z:").toPath(), foreign.toPath())

        BuranPrivatePrefixFiles.copySeed(source.toPath(), prefix.toPath())
        BuranPrivatePrefixFiles.setPrivateDrives(prefix)
        BuranPrivatePrefixFiles.setPrivateDrives(prefix)

        assertEquals("private save", save.readText())
        assertEquals("private save", File(privateDrive, "settings-link").readText())
        assertFalse(Files.exists(File(privateDrive, "shared").toPath(), NOFOLLOW_LINKS))
        assertEquals(setOf("c:", "z:"), existingDrives.list()!!.toSet())
        assertEquals(Paths.get("../drive_c"), Files.readSymbolicLink(File(existingDrives, "c:").toPath()))
        assertEquals(Paths.get("/"), Files.readSymbolicLink(File(existingDrives, "z:").toPath()))
        assertEquals(foreignDrive.toPath(), Files.readSymbolicLink(File(sourceDrives, "c:").toPath()))
        assertEquals("foreign", File(foreignDrive, "sentinel").readText())
    }

    @Test
    fun firstSeedDoesNotCopyDosdevicesEvenWhenTheTemplateContainsRegularEntries() {
        val source = tmp.newFolder("template")
        File(source, "dosdevices").mkdir()
        File(source, "dosdevices/placeholder").writeText("template drive")
        File(source, "drive_c").mkdir()
        File(source, "user.reg").writeText("registry")
        val destination = File(tmp.newFolder("game"), ".wine")

        BuranPrivatePrefixFiles.copySeed(source.toPath(), destination.toPath())

        assertFalse(Files.exists(File(destination, "dosdevices").toPath(), NOFOLLOW_LINKS))
        assertTrue(File(destination, "drive_c").isDirectory)
        assertEquals("registry", File(destination, "user.reg").readText())
    }

    @Test
    fun launchCleanupRemovesOnlyItsOwnTemporaryProjection() {
        assumeTrue("Symbolic link fixture requires a POSIX filesystem", File.separatorChar == '/')
        val game = tmp.newFolder("game")
        val first = tmp.newFolder("first-projection").canonicalFile
        val next = tmp.newFolder("second-projection").canonicalFile
        val link = File(game, "common")

        BuranPrivatePrefixFiles.replaceManagedLink(link, first)
        BuranPrivatePrefixFiles.replaceManagedLink(link, next)
        BuranPrivatePrefixFiles.removeManagedLink(link, first)
        assertEquals(next, link.canonicalFile)
        BuranPrivatePrefixFiles.removeManagedLink(link, next)
        assertFalse(Files.exists(link.toPath(), NOFOLLOW_LINKS))
        assertTrue(first.isDirectory)
        assertTrue(next.isDirectory)
    }

    @Test
    fun steamHostUsesTheSharedTitleAndPreservesConflictingLegacyProgress() {
        assumeTrue("Symbolic link fixture requires a POSIX filesystem", File.separatorChar == '/')
        val steam = tmp.newFolder("steam-host").canonicalFile
        val title = steam.resolve("userdata/42/35140")
        val legacy = title.resolve("remote").apply { mkdirs() }
        legacy.resolve("Save0.sgd").writeText("Buran progress")
        legacy.resolve("profile.bin").writeText("Buran settings")
        title.resolve("remotecache.vdf").writeText("Buran cache")
        val shared = tmp.newFolder("shared").canonicalFile.resolve("42/35140/remote").apply { mkdirs() }
        shared.resolve("Save0.sgd").writeText("Winlator progress")
        shared.parentFile!!.resolve("remotecache.vdf").writeText("Winlator cache")

        BuranPrivatePrefixFiles.linkSteamHostUserdata(steam, 42, 35140, shared)
        BuranPrivatePrefixFiles.linkSteamHostUserdata(steam, 42, 35140, shared)

        assertEquals(shared.parentFile, title.canonicalFile)
        assertEquals("Winlator progress", title.resolve("remote/Save0.sgd").readText())
        assertEquals("Buran settings", shared.resolve("profile.bin").readText())
        assertEquals("Winlator cache", title.resolve("remotecache.vdf").readText())
        val backup = title.parentFile!!.listFiles()!!.single { it.name.startsWith(".buran-legacy-35140-") }
        assertEquals("Buran progress", backup.resolve("35140/remote/Save0.sgd").readText())
        assertEquals("Buran cache", backup.resolve("35140/remotecache.vdf").readText())
    }
}
