package org.buran.runtime

import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream

internal object TurnipArchive {
    data class Metadata(val name: String, val libraryName: String, val driverVersion: String) {
        val title: String get() = if (driverVersion.isBlank()) name else "$name · $driverVersion"
    }

    const val archiveLimit = 96L * 1024 * 1024
    private const val expandedLimit = 160L * 1024 * 1024
    private const val libraryLimit = 64L * 1024 * 1024
    private const val metadataLimit = 64L * 1024
    private val fileName = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")
    private val libraryName = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,119}\\.so(?:\\.[0-9]+)*")

    fun unpack(archive: File, output: File, androidApi: Int): Metadata {
        val expectedEntries = zipEntryCount(archive)
        var expanded = 0L
        val names = mutableSetOf<String>()
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                val fileType = entry.unixMode and 0xf000
                require(fileName.matches(name) && !entry.isDirectory && (fileType == 0 || fileType == 0x8000)) {
                    "Недопустимая запись в ZIP: $name"
                }
                require(names.size < 64 && names.add(name)) { "Повторяющиеся записи или слишком много файлов в ZIP" }
                require(zip.canReadEntryData(entry)) { "ZIP использует неподдерживаемое сжатие или шифрование" }
                val isLibrary = libraryName.matches(name)
                require(isLibrary || name.endsWith(".json") || name.endsWith(".txt") ||
                    name.endsWith(".md") || name in setOf("README", "LICENSE", "COPYING", "NOTICE")) {
                    "Неподдерживаемый файл драйвера: $name"
                }
                val limit = if (isLibrary) libraryLimit else metadataLimit
                require(entry.size in 0..limit && entry.size <= expandedLimit - expanded) {
                    "Файлы драйвера превышают допустимый размер"
                }
                val destination = output.resolve(name)
                val crc = CRC32()
                val copied = CheckedInputStream(zip.getInputStream(entry), crc).use { input ->
                    destination.outputStream().use { out -> copyBounded(input, out, entry.size) }
                }
                require(copied == entry.size && crc.value == entry.crc) { "Повреждённый файл ZIP: $name" }
                expanded += copied
                if (isLibrary) validateLibrary(destination)
                check(destination.setReadOnly()) { "Не удалось защитить файл драйвера от изменений" }
            }
        }
        require(names.size == expectedEntries) { "Повреждённый каталог ZIP" }
        return readMetadata(output, androidApi)
    }

    private fun zipEntryCount(archive: File): Int {
        RandomAccessFile(archive, "r").use { file ->
            require(file.length() in 22..archiveLimit) { "Некорректный размер ZIP" }
            val tail = ByteBuffer.allocate(minOf(file.length(), 22 + 65535L).toInt()).order(ByteOrder.LITTLE_ENDIAN)
            val tailOffset = file.length() - tail.capacity()
            file.seek(tailOffset)
            file.readFully(tail.array())
            fun u16(offset: Int): Int = tail.getShort(offset).toInt() and 0xffff
            fun u32(offset: Int): Long = tail.getInt(offset).toLong() and 0xffffffffL
            for (offset in tail.capacity() - 22 downTo 0) {
                if (tail.getInt(offset) != 0x06054b50 || offset + 22 + u16(offset + 20) != tail.capacity()) continue
                val count = u16(offset + 10)
                val centralSize = u32(offset + 12)
                val centralStart = u32(offset + 16)
                // Driver bundles are small, single-volume ZIPs. Bound central-directory allocation before parsing it.
                require(count in 1..64 && count == u16(offset + 8) && tail.getInt(offset + 4) == 0 &&
                    centralSize in 46..(128L * 1024) && centralStart + centralSize == tailOffset + offset) {
                    "Неподдерживаемый или повреждённый каталог ZIP"
                }
                return count
            }
        }
        throw IllegalArgumentException("Файл не является корректным ZIP")
    }

    fun readMetadata(directory: File, androidApi: Int): Metadata {
        val metadataFile = directory.resolve("meta.json")
        require(metadataFile.isFile && metadataFile.length() in 1..metadataLimit) {
            "В корне ZIP должен быть файл meta.json"
        }
        val json = JSONObject(metadataFile.readText())
        val schema = if (json.has("schemaVersion")) json.opt("schemaVersion") else 1
        require(schema == 1) { "Неподдерживаемая версия формата meta.json" }
        val minApi = json.opt("minApi")
        require(minApi is Int && minApi > 0) { "В meta.json отсутствует корректный minApi" }
        require(minApi <= androidApi) { "Драйвер требует Android API $minApi, на устройстве API $androidApi" }
        val name = (json.opt("name") as? String)?.trim().orEmpty()
        val library = json.opt("libraryName") as? String
        require(name.isNotEmpty() && name.length <= 128 && name.none(Char::isISOControl)) {
            "В meta.json отсутствует корректное имя драйвера"
        }
        require(library != null && libraryName.matches(library)) {
            "В meta.json отсутствует корректное имя библиотеки"
        }
        val version = (json.opt("driverVersion") as? String)?.trim().orEmpty()
        require(version.length <= 128 && version.none(Char::isISOControl)) { "Некорректная версия драйвера" }
        validateLibrary(directory.resolve(library))
        return Metadata(name, library, version)
    }

    fun validateLibrary(library: File) {
        fun reject(): Nothing = throw IllegalArgumentException(
            "Ожидалась 64-битная общая библиотека AArch64: ${library.name}"
        )
        if (!library.isFile || library.length() !in 64..libraryLimit) reject()
        RandomAccessFile(library, "r").use { file ->
            val header = ByteBuffer.wrap(ByteArray(64)).order(ByteOrder.LITTLE_ENDIAN)
            file.readFully(header.array())
            // ET_DYN, EM_AARCH64, ELFCLASS64, ELFDATA2LSB, EV_CURRENT.
            if (header.getInt(0) != 0x464c457f || header.get(4).toInt() != 2 ||
                header.get(5).toInt() != 1 || header.get(6).toInt() != 1 ||
                header.getShort(16).toInt() != 3 || header.getShort(18).toInt() != 183 ||
                header.getInt(20) != 1 || header.getShort(52).toInt() != 64
            ) reject()
            val programHeaders = header.getLong(32)
            val entrySize = header.getShort(54).toInt()
            val entries = header.getShort(56).toInt()
            if (entrySize != 56 || entries !in 1..256 ||
                programHeaders !in 64..(file.length() - entrySize.toLong() * entries)
            ) reject()

            file.seek(programHeaders)
            val entry = ByteBuffer.wrap(ByteArray(entrySize)).order(ByteOrder.LITTLE_ENDIAN)
            var loadable = false
            var dynamic = false
            repeat(entries) {
                file.readFully(entry.array())
                when (entry.getInt(0)) {
                    1, 2 -> {
                        val offset = entry.getLong(8)
                        val size = entry.getLong(32)
                        if (offset !in 0..file.length() || size !in 0..(file.length() - offset)) reject()
                        if (entry.getInt(0) == 1 && size > 0) loadable = true
                        if (entry.getInt(0) == 2 && size >= 16) dynamic = true
                    }
                    3 -> reject() // PT_INTERP denotes a program, not a loadable driver.
                }
            }
            if (!loadable || !dynamic) reject()
        }
    }

    fun copyBounded(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count == -1) return total
            total += count
            require(total <= limit) { "Архив или файл драйвера слишком большой" }
            output.write(buffer, 0, count)
        }
    }
}
