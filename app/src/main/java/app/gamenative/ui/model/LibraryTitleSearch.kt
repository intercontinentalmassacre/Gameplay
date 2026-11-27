package app.gamenative.ui.model

import app.gamenative.utils.unaccent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal class LibraryTitleSearch {
    private val titles = ConcurrentHashMap<String, TitleKey>()

    fun matcher(query: String): (String) -> Boolean {
        if (query.isBlank()) return { true }
        val terms = searchWords(query)
        if (terms.isEmpty()) return { title -> title.contains(query, ignoreCase = true) }
        val compactQuery = terms.joinToString("")

        return { title ->
            val key = titles.computeIfAbsent(title) {
                val words = searchWords(it)
                TitleKey(words.joinToString(" "), words.joinToString(""))
            }
            terms.all { term -> term in key.spaced } || compactQuery in key.compact
        }
    }

    private data class TitleKey(val spaced: String, val compact: String)
}

private val searchWord = Regex("[\\p{L}\\p{N}]+")

private fun searchWords(text: String): List<String> = searchWord
    .findAll(text.replace("™", "").unaccent().lowercase(Locale.ROOT))
    .map { it.value }
    .toList()
