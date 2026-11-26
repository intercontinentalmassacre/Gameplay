package app.gamenative.display

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GamescopePrefixTest {
    @Test fun preservesExistingAliasesForTheReleasePackage() {
        val files = File("/data/user/0/app.gameplay/files").absoluteFile
        assertEquals(File(files, "gsc"), GamescopePayload.xwaylandPrefix(files))
        assertEquals(File(files, "gsx"), GamescopePayload.xwaylandPrefix(files, experimental = true))
    }

    @Test fun longPackageUsesSeparatePrivateAliasesWithinTheBinaryLimit() {
        val root = File("/data/user/0/app.gameplay.itchcheck").absoluteFile
        val files = File(root, "files")
        assertEquals(File(root, "g"), GamescopePayload.xwaylandPrefix(files))
        assertEquals(File(root, "x"), GamescopePayload.xwaylandPrefix(files, experimental = true))
    }
}
