package org.buran.runtime

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import java.io.File

internal object Box64State {
    private const val standard = "box64"
    private const val legacyDxvk = "box64-dxvk"
    private const val ambiguous = "Для этой игры есть два префикса Box64. Выберите префикс в Buran."

    private fun candidates(root: File): List<String> =
        listOf(standard, legacyDxvk).filter { root.resolve("$it/prefix").isDirectory }.ifEmpty {
            // Preserve the legacy directory even if its first launch never reached Wine.
            listOf(if (root.resolve(legacyDxvk).isDirectory && !root.resolve(standard).exists()) legacyDxvk else standard)
        }

    @Synchronized
    private fun select(root: File, choice: String? = null, persist: Boolean = false): File? {
        val selection = root.resolve(".buran-box64-prefix")
        val saved = selection.takeIf(File::isFile)?.readText()
        if (saved != null) {
            check(saved == standard || saved == legacyDxvk) { "Не удалось прочитать выбранный префикс Box64" }
            return root.resolve(saved)
        }
        val options = candidates(root)
        val selected = options.singleOrNull() ?: choice?.takeIf(options::contains) ?: return null
        if (persist) Containers.write(selection, selected)
        return root.resolve(selected)
    }

    fun directory(root: File): File = checkNotNull(select(root)) { ambiguous }

    fun choose(activity: Activity, root: File, launch: () -> Unit) {
        fun start(choice: String? = null): Boolean {
            val state = try {
                select(root, choice, persist = true)
            } catch (error: Exception) {
                Toast.makeText(activity, error.message, Toast.LENGTH_LONG).show()
                return true
            }
            if (state == null) return false
            launch()
            return true
        }
        if (start()) return
        AlertDialog.Builder(activity)
            .setTitle("Выберите сохранения Box64")
            .setMessage("У игры два префикса Wine с отдельными сохранениями. Выбранный префикс будет использоваться с DXVK и без него. Оба префикса останутся на диске.")
            .setPositiveButton("Прежний Box64") { _, _ -> start(standard) }
            .setNeutralButton("Прежний Box64 + DXVK") { _, _ -> start(legacyDxvk) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
