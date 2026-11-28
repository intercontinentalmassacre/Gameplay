package org.buran.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

data class InstalledGame(val id: String, val exe: String, val bits: Int) {
    fun directory(context: Context): File = File(gamesDirectory(context), id)

    internal fun select(context: Context) {
        selected(context)?.takeIf { it.id != id }?.rememberExecutable(context)
        rememberExecutable(context)
        check(context.buranPreferences("games", Context.MODE_PRIVATE).edit()
            .putString("id", id).putString("exe", exe).putInt("bits", bits).commit()) {
            "Не удалось сохранить выбранную игру"
        }
    }

    private fun rememberExecutable(context: Context) {
        val record = File(gamesDirectory(context), ".library/$id")
        if (!directory(context).isDirectory || readRecord(context, id) == this) return
        Files.createDirectories(record.toPath().parent)
        val atomic = AtomicFile(record)
        val output = atomic.startWrite()
        try {
            output.write("$bits\n$exe".toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            check(readRecord(context, id) == this) { "Не удалось сохранить EXE игры" }
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        private val gameId = Regex("[a-f0-9]{32}")

        fun selected(context: Context): InstalledGame? {
            val prefs = context.buranPreferences("games", Context.MODE_PRIVATE)
            val id = prefs.getString("id", null) ?: return null
            val exe = prefs.getString("exe", null) ?: return null
            val bits = prefs.getInt("bits", 0)
            return parse(id, exe, bits)?.takeIf {
                Files.isDirectory(it.directory(context).toPath(), NOFOLLOW_LINKS)
            }
        }

        internal fun library(context: Context): List<LibraryGame> {
            val selected = selected(context)
            return gamesDirectory(context).listFiles().orEmpty()
                .filter { gameId.matches(it.name) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
                .map { directory ->
                    val game = (readRecord(context, directory.name) ?: selected?.takeIf { it.id == directory.name })
                        ?.takeIf { directory.resolve(it.exe).isFile }
                    LibraryGame(directory.name, game)
                }
                .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        }

        private fun readRecord(context: Context, id: String): InstalledGame? {
            return runCatching {
                val record = File(gamesDirectory(context), ".library/$id")
                val backup = File(record.path + ".bak")
                // AtomicFile.openRead can remove a .new file owned by a concurrent writer.
                val fields = (backup.takeIf(File::isFile) ?: record)
                    .readText(Charsets.UTF_8).split('\n', limit = 2)
                if (fields.size == 2) parse(id, fields[1], fields[0].toIntOrNull() ?: 0) else null
            }.getOrNull()
        }

        private fun parse(id: String, exe: String, bits: Int): InstalledGame? {
            if (!gameId.matches(id) || bits !in listOf(32, 64) ||
                exe.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                exe.any { it.isISOControl() || it in "\\:<>\"|?*" }
            ) return null
            return InstalledGame(id, exe, bits)
        }

        internal fun fromRegistration(output: String, exe: String): InstalledGame {
            val fields = output.trimEnd('\n').split('\t')
            check(fields.size == 2 && gameId.matches(fields[0]) && fields[1] in listOf("32", "64")) {
                "Не удалось прочитать результат регистрации игры"
            }
            return InstalledGame(fields[0], exe, fields[1].toInt())
        }
    }
}

internal data class LibraryGame(val id: String, val game: InstalledGame?) {
    val name: String
        get() = game?.exe?.substringAfterLast('/') ?: "Игра ${id.take(8)}"

    fun findExecutables(context: Context): List<String> {
        val directory = File(gamesDirectory(context), id)
        val binary = File(context.applicationInfo.nativeLibraryDir, "libburan.so")
        val process = ProcessBuilder(binary.path, "game-executables", directory.path)
            .redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(process.waitFor() == 0) { "Не удалось найти EXE игры. ${output.trim()}" }
            return output.lineSequence().filter(String::isNotEmpty).toList()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun recover(context: Context, exe: String): InstalledGame {
        val directory = File(gamesDirectory(context), id)
        return InstalledGame(id, exe, gameExecutableBits(context, directory, exe))
    }
}

internal fun gameExecutableBits(context: Context, directory: File, exe: String): Int {
    val binary = File(context.applicationInfo.nativeLibraryDir, "libburan.so")
    val process = ProcessBuilder(binary.path, "game-executable-bits", directory.path, exe)
        .redirectErrorStream(true).start()
    try {
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0 && output in listOf("32", "64")) {
            "Не удалось открыть EXE. $output"
        }
        return output.toInt()
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

internal fun gamesDirectory(context: Context) = File(context.buranFilesDir, "games")

