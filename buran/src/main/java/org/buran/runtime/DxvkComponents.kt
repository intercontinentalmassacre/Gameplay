package org.buran.runtime

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object DxvkComponents {
    const val EXTRA = "buranGraphics"
    private val names = setOf("d3d8.dll", "d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
    private val required = setOf("d3d11.dll", "dxgi.dll")

    class Installed internal constructor(internal val x64: Map<String, File>, internal val x86: Map<String, File>) {
        fun files(bits: Int): Map<String, File> =
            (if (bits == 64) x64 else x86).takeIf { it.keys.containsAll(required) }.orEmpty()
    }

    fun resolve(context: Context, graphics: Bundle?): Installed? {
        val entries = graphics?.getStringArrayList("dxvkFiles") ?: return null
        require(entries.size <= names.size * 2) { "Неверный состав пакета DXVK" }
        val roots = listOf(context.filesDir.resolve("contents/DXVK"),
            context.buranFilesDir.resolve("dxvk-selected")).map { it.canonicalFile.toPath() }
        val x64 = linkedMapOf<String, File>()
        val x86 = linkedMapOf<String, File>()
        for (entry in entries) {
            val target = entry.substringBefore('=')
            val name = target.substringAfter('/')
            require('=' in entry && name in names) { "Неверный файл пакета DXVK" }
            val source = File(entry.substringAfter('=')).canonicalFile
            require(roots.any { source.toPath().startsWith(it) } && source.isFile) { "Пакет DXVK недоступен" }
            when (target.substringBefore('/')) {
                "system32" -> if (isWindowsDll(source, 0x8664, 0x20b)) x64.putUnique(name, source)
                "syswow64" -> if (isWindowsDll(source, 0x014c, 0x10b)) x86.putUnique(name, source)
            }
        }
        return Installed(x64, x86)
    }

    private fun MutableMap<String, File>.putUnique(name: String, source: File) {
        require(put(name, source) == null) { "Повторяющийся файл DXVK: $name" }
    }

    private fun isWindowsDll(source: File, machine: Int, magic: Int): Boolean =
        RandomAccessFile(source, "r").use { file ->
            if (file.length() < 90 || file.readUnsignedShort() != 0x4d5a) return@use false
            file.seek(0x3c)
            val pe = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()))
            if (pe !in 64..(file.length() - 26)) return@use false
            file.seek(pe)
            if (file.readInt() != 0x50450000 || file.readUnsignedShort() !=
                (java.lang.Short.reverseBytes(machine.toShort()).toInt() and 0xffff)
            ) return@use false
            file.seek(pe + 22)
            val characteristics = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
            val optionalMagic = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
            characteristics and 0x2000 != 0 && optionalMagic == magic
        }

    fun install(prefix: File, installed: Installed?, bits: Int): String? {
        val files = installed?.files(bits).orEmpty()
        if (files.isEmpty()) return null
        val windows = prefix.resolve("drive_c/windows").canonicalFile
        val destination = windows.resolve(if (bits == 64) "system32" else "syswow64").canonicalFile
        require(destination.parentFile == windows && destination.isDirectory) { "Каталог DLL Wine недоступен" }
        copyInstalled(files, destination)
        return files.keys.joinToString(",") { it.removeSuffix(".dll") }
    }

    fun copyInstalled(files: Map<String, File>, directory: File) {
        for ((name, source) in files) {
            val staged = Files.createTempFile(directory.toPath(), ".dxvk-", ".tmp")
            try {
                Files.copy(source.toPath(), staged, REPLACE_EXISTING)
                Files.move(staged, directory.resolve(name).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staged)
            }
        }
    }

    fun overrides(nativeFiles: Set<String>): String {
        val native = nativeFiles.joinToString(",") { it.removeSuffix(".dll") }
        val builtin = (names - nativeFiles).joinToString(",") { it.removeSuffix(".dll") }
        return listOfNotNull(native.takeIf(String::isNotEmpty)?.plus("=n"),
            builtin.takeIf(String::isNotEmpty)?.plus("=b")).joinToString(";")
    }

    fun source(context: Context): File {
        val payload = context.assets.open("dxvk/payload.id").bufferedReader().use { it.readText().trim() }
        check(payload.matches(Regex("[0-9a-f]{64}"))) { "Неверный идентификатор комплекта DXVK" }
        val directory = context.buranFilesDir.resolve("dxvk-runtime/$payload").apply { mkdirs() }
        install(context, directory)
        return directory
    }

    fun install(context: Context, drive: File) {
        for (name in listOf("d3d11.dll", "dxgi.dll")) {
            val destination = drive.resolve(name).toPath()
            if (Files.exists(destination, NOFOLLOW_LINKS)) continue

            val staged = Files.createTempFile(drive.toPath(), ".dxvk-", ".tmp")
            try {
                context.assets.open("dxvk/$name").use { input ->
                    Files.newOutputStream(staged).use { output -> input.copyTo(output) }
                }
                try {
                    // Publish the complete file atomically while preserving any existing entry.
                    Files.createLink(destination, staged)
                } catch (_: FileAlreadyExistsException) {
                    // Another install completed first.
                }
            } finally {
                Files.deleteIfExists(staged)
            }
        }
    }
}
