package app.gamenative.localgames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuranPhysXDependenciesTest {
    @Test
    fun copiesOnlyVerifiedAgeiaSubtreeAndPreservesOtherRegistryKeys() {
        val source = """
            WINE REGISTRY Version 2

            [Software\\Other] 1
            "private"="do not copy"

            [Software\\Wow6432Node\\AGEIA Technologies] 2
            "PhysXCore Path"="C:\\Program Files (x86)\\AGEIA Technologies"
            "PhysX Version"=dword:008ab6fe

            [Software\\Wow6432Node\\AGEIA Technologies\\PhysX_A32_Engines] 3
            "2.7.0"=dword:00000015
        """.trimIndent()
        val target = """
            WINE REGISTRY Version 2

            [Software\\Wow6432Node\\AGEIA Technologies] 4
            "PhysX Version"=dword:00000000

            [Software\\Wow6432Node\\AGEIA Technologies-Legacy] 5
            @="keep this"
        """.trimIndent()
        val result = requireNotNull(BuranPhysXDependencies.mergeRegistry(source, target))

        assertTrue(result.contains("dword:008ab6fe"))
        assertTrue(result.contains("\"2.7.0\"=dword:00000015"))
        assertTrue(result.contains("@=\"keep this\""))
        assertFalse(result.contains("dword:00000000"))
        assertFalse(result.contains("do not copy"))
        assertEquals(result, BuranPhysXDependencies.mergeRegistry(source, result))
        assertNull(BuranPhysXDependencies.mergeRegistry(source.replace("PhysXCore Path", "Some other path"), target))
    }
}
