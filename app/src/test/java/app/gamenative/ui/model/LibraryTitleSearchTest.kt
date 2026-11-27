package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTitleSearchTest {
    private val search = LibraryTitleSearch()

    @Test
    fun matchesWordsInAnyOrderAndAllowsPartialTerms() {
        val matches = search.matcher("  automata   nier  ")
        assertTrue(matches("NieR:Automata™"))
        assertFalse(matches("NieR Replicant"))
        assertTrue(search.matcher("auto nie")("NieR:Automata™"))
    }

    @Test
    fun foldsDiacriticsAndIgnoresPunctuation() {
        assertTrue(search.matcher("pokemon")("Pokémon"))
        assertTrue(search.matcher("Pokémon")("Pokemon"))
        assertTrue(search.matcher("spider man")("Marvel’s Spider-Man"))
        assertTrue(search.matcher("spiderman")("Marvel’s Spider-Man"))
        assertTrue(search.matcher("龙 纪元")("龙腾世纪：纪元"))
    }

    @Test
    fun blankQueryPreservesOrderAndPunctuationOnlySearchIsLiteral() {
        val titles = listOf("Rez", "Ikaruga", "Q.U.B.E.")
        assertEquals(titles, titles.filter(search.matcher("")))
        assertEquals(titles, titles.filter(search.matcher("  ")))
        assertEquals(listOf("Q.U.B.E."), titles.filter(search.matcher(".")))
    }
}
