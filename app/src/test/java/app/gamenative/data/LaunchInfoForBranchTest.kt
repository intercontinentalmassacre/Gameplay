package app.gamenative.data

import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.utils.generateSteamApp
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LaunchInfoForBranchTest {
    @Test
    fun parsesBranchRestrictionsFromSteamMetadata() {
        val app = KeyValue.loadFromString("""
            "appinfo" {
                "appid" "42"
                "config" { "launch" {
                    "0" { "executable" "dev.exe" "config" { "betakey" "development" } }
                    "1" { "executable" "game.exe" }
                } }
            }
        """.trimIndent())!!.generateSteamApp()
        assertEquals(listOf("game.exe"), app.config.launch.forBranch { "public" }.map { it.executable })
    }

    private fun entry(option: Int, exe: String, betaKey: String = "", os: OS = OS.windows, arch: OSArch = OSArch.Arch64) =
        LaunchInfo(
            executable = exe,
            workingDir = "",
            description = "option $option",
            type = "default",
            configOS = EnumSet.of(os),
            configArch = arch,
            betaKey = betaKey,
        )

    private val hogwarts = listOf(
        entry(0, "Phoenix/Binaries/Win64/HogwartsLegacy.exe", betaKey = "development"),
        entry(9, "HogwartsLegacy.exe", betaKey = "release"),
        entry(13, "HogwartsLegacy.exe"),
    )

    @Test
    fun listWithoutBetaKeysIsReturnedAsIsWithoutLookingUpTheBranch() {
        val list = listOf(
            entry(0, "game32.exe", arch = OSArch.Arch32),
            entry(1, "game.exe", os = OS.linux),
            entry(2, "game64.exe"),
        )
        var asked = false
        val result = list.forBranch { asked = true; "beta" }
        assertSame(list, result)
        assertFalse(asked)
    }

    @Test
    fun publicBranchSkipsBetaOnlyEntries() {
        assertEquals(listOf("option 13"), hogwarts.forBranch { "public" }.map { it.description })
        assertEquals(listOf("option 13"), hogwarts.forBranch { null }.map { it.description })
        assertEquals(listOf("option 13"), hogwarts.forBranch { "" }.map { it.description })
    }

    @Test
    fun installedBetaBranchKeepsItsEntryFirst() {
        assertEquals(listOf("option 0", "option 13"), hogwarts.forBranch { "development" }.map { it.description })
        assertEquals(listOf("option 9", "option 13"), hogwarts.forBranch { "release" }.map { it.description })
    }

    @Test
    fun betaKeyListsSeveralBranches() {
        val list = listOf(entry(0, "check.exe", betaKey = "check_monstars, dev_monstars"), entry(1, "game.exe", betaKey = "public"))
        assertEquals(listOf("option 0"), list.forBranch { "dev_monstars" }.map { it.description })
        assertEquals(listOf("option 0"), list.forBranch { "CHECK_MONSTARS" }.map { it.description })
        assertEquals(listOf("option 1"), list.forBranch { "public" }.map { it.description })
    }

    @Test
    fun filterThatLeavesNothingReturnsTheListUnchanged() {
        val list = listOf(entry(0, "a.exe", betaKey = "development"), entry(1, "b.exe", betaKey = "release"))
        assertSame(list, list.forBranch { "public" })
    }
}
