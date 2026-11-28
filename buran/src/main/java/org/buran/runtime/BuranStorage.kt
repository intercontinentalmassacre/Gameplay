package org.buran.runtime

import android.content.Context
import java.io.File

internal val Context.isBuranEmbedded: Boolean
    get() = packageName == "app.gameplay" || packageName.startsWith("app.gameplay.")

internal val Context.buranFilesDir: File
    get() = if (isBuranEmbedded) filesDir.resolve("buran").apply { mkdirs() } else filesDir

internal val Context.buranCacheDir: File
    get() = if (isBuranEmbedded) cacheDir.resolve("buran").apply { mkdirs() } else cacheDir

internal fun Context.buranPreferences(name: String, mode: Int) =
    getSharedPreferences(if (isBuranEmbedded) "buran-$name" else name, mode)

internal val Context.buranDisplayRoot: File
    get() = File("/data/data/$packageName", if (isBuranEmbedded) "buran-fs" else "fs")
