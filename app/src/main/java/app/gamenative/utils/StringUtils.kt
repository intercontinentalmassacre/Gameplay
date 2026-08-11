package app.gamenative.utils

import android.text.Html
import app.gamenative.Constants
import java.text.Normalizer
import java.util.Locale

private val REGEX_UNACCENT = "\\p{M}+".toRegex()
private val REGEX_FILENAME_UNSAFE = Regex("[^a-zA-Z0-9_-]")
private val REGEX_NON_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]")


/**
 * Extension functions relating to [String] as the receiver type.
 */

fun String.getAvatarURL(): String =
    this.ifEmpty { null }
        ?.takeIf { str -> str.isNotEmpty() && !str.all { it == '0' } }
        ?.let { "${Constants.Persona.AVATAR_BASE_URL}${it.substring(0, 2)}/${it}_full.jpg" }
        ?: Constants.Persona.MISSING_AVATAR_URL

fun String.fromHtml(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()

fun CharSequence.unaccent(): String {
    val temp = Normalizer.normalize(this, Normalizer.Form.NFKD)
    return REGEX_UNACCENT.replace(temp, "")
}

/** Stable, Unicode-safe key for matching the same title across different stores. */
fun CharSequence.normalizeForComparison(): String = unaccent()
    .lowercase(Locale.ROOT)
    .replace(REGEX_NON_LETTER_OR_DIGIT, "")

/**
 * Replaces any character that isn't ASCII alphanumeric, underscore, or hyphen with an
 * underscore. Intended for turning identifiers (app names, namespaces, catalog ids)
 * into safe filename components.
 */
fun String.sanitizeForFilename(): String = REGEX_FILENAME_UNSAFE.replace(this, "_")
