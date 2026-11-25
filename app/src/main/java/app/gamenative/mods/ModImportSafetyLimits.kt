package app.gamenative.mods

import android.content.Context
import app.gamenative.R

internal object ModImportSafetyLimits {
    const val MAX_ENTRIES = 50_000
    private const val MAX_CONTENT_GIB = 150L
    const val MAX_CONTENT_BYTES = MAX_CONTENT_GIB * 1024L * 1024L * 1024L
    const val MAX_RELATIVE_PATH_LENGTH = 1_024
    const val MAX_DIRECTORY_DEPTH = 64

    fun sizeLimitMessage(context: Context): String =
        context.getString(R.string.local_mod_too_large, MAX_CONTENT_GIB)
}
