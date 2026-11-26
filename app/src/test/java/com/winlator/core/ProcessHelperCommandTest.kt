package com.winlator.core

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ProcessHelperCommandTest {
    @Test fun passesTheWineExecutablePathAsOneArgumentWithoutLiteralQuotes() {
        val path = "C:\\itch\\Smiling Terror.exe"
        assertArrayEquals(
            arrayOf("wine", "explorer", "/desktop=shell,1280x720", path),
            ProcessHelper.splitCommand("wine explorer /desktop=shell,1280x720 \"$path\""),
        )
    }

    @Test fun keepsEmptyArgumentsEscapedSpacesAndApostrophes() {
        assertArrayEquals(
            arrayOf("cmd", "", "My Folder", "player's", "two words"),
            ProcessHelper.splitCommand("cmd \"\" My\\ Folder player's 'two words'"),
        )
    }
}
