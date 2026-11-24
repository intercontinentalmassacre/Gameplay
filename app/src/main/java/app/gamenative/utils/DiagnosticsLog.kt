package app.gamenative.utils

import android.content.Context
import com.winlator.xenvironment.ImageFs
import java.io.File

object DiagnosticsLog {
    fun file(context: Context, appId: String): File =
        File(ImageFs.find(context).rootDir, "usr/tmp/wrapper_diag_$appId.txt")

    fun wineFile(context: Context, appId: String): File {
        val key = java.util.UUID.nameUUIDFromBytes(appId.toByteArray(Charsets.UTF_8))
        return File(context.getExternalFilesDir(null), "wine_logs/wine_$key.log")
    }

    fun exists(context: Context, appId: String): Boolean =
        file(context, appId).let { it.exists() && it.length() > 0 }
}
