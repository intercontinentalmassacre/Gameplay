package org.buran.runtime

import android.content.Context
import android.util.AtomicFile
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.DigestInputStream
import java.security.MessageDigest

internal object WcpFiles {
    private const val maxArchive = 512L * 1024 * 1024
    private const val maxUnpacked = 2L * 1024 * 1024 * 1024
    private const val maxDll = 16L * 1024 * 1024
    private const val maxProfile = 64 * 1024L
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+~-]{0,95}")
    private val installedPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+~-]{0,95}_[0-9]{1,10}_[0-9a-f]{64}")
    private val dlls = mapOf("libarm64ecfex.dll" to 0xa641, "libwow64fex.dll" to 0xaa64)
    private val optionalCompilers = setOf("FEXOfflineCompiler32.exe", "FEXOfflineCompiler64.exe")

    private fun root(context: Context) = File(context.buranFilesDir, "wcp/fex")
    private fun selection(context: Context) = AtomicFile(File(root(context), "active"))

    @Synchronized
    fun activeFexDirectory(context: Context): File? {
        val key = try {
            selection(context).readFully().toString(Charsets.UTF_8)
        } catch (_: java.io.FileNotFoundException) {
            return null
        }
        if (!installedPattern.matches(key)) return null
        return File(root(context), key).takeIf { dir -> dlls.keys.all { dir.resolve(it).isFile } }
    }

    fun validFexDirectory(directory: File): Boolean = runCatching {
        for ((name, machine) in dlls) {
            val file = directory.resolve(name)
            require(file.canonicalFile.parentFile == directory && file.length() in 1..maxDll)
            requirePe(file, machine)
        }
        true
    }.getOrDefault(false)

    fun validWowBox64(file: File): Boolean = runCatching {
        require(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) == 4096L)
        require(file.length() in 1..maxDll)
        requirePe(file, 0xaa64)
        true
    }.getOrDefault(false)

    fun activeLabel(context: Context): String {
        val dir = activeFexDirectory(context) ?: return "Встроенный FEX"
        val profile = JSONObject(dir.resolve("profile.json").readText())
        return "${profile.getString("versionName")} (${profile.getInt("versionCode")})"
    }

    @Synchronized
    fun useBundled(context: Context) = selection(context).delete()

    fun importFex(context: Context, input: InputStream): String {
        val parent = root(context)
        check(parent.isDirectory || parent.mkdirs()) { "Не удалось создать каталог импорта" }
        val staging = java.nio.file.Files.createTempDirectory(parent.toPath(), ".incoming-").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val archive = staging.resolve("original.wcp")
            archive.outputStream().use { out ->
                DigestInputStream(LimitedInput(input, maxArchive), digest).copyTo(out)
                out.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val profile = readProfile(archive)
            val json = JSONObject(profile)
            val type = json.opt("type") as? String ?: error("В profile.json отсутствует тип WCP")
            require(type.equals("FEXCore", ignoreCase = true)) { "Тип WCP «${type.take(80)}» не поддерживается. Доступен FEXCore ARM64EC." }
            val version = json.opt("versionName") as? String
            require(version != null && versionPattern.matches(version)) { "Некорректное имя версии FEXCore" }
            val revision = json.opt("versionCode")
            require(revision is Int && revision >= 0) { "Некорректный номер версии FEXCore" }
            val mappings = requireNotNull(json.optJSONArray("files")) { "В профиле FEXCore отсутствуют сопоставления файлов" }
            require(mappings.length() in 2..4) { "Этот набор компонентов FEXCore не поддерживается" }
            val files = mutableMapOf<String, String>()
            val targets = mutableSetOf<String>()
            for (index in 0 until mappings.length()) {
                val mapping = mappings.getJSONObject(index)
                val source = safePath(mapping.getString("source"))
                val target = mapping.getString("target")
                val name = target.removePrefix("\${system32}/")
                require(target == "\${system32}/$name" && (name in dlls || name in optionalCompilers)) {
                    "Неподдерживаемое сопоставление FEXCore. Требуются DLL для \${system32}."
                }
                require(targets.add(name)) { "Повторное сопоставление для $name" }
                if (name in dlls) require(files.put(source, name) == null) { "Повторный исходный файл FEXCore" }
            }
            require(files.values.toSet() == dlls.keys) { "Профиль должен содержать обе DLL FEXCore" }
            extractDlls(archive, staging, files)
            staging.resolve("profile.json").writeText(profile)
            if (compression(archive) == "xz") check(archive.renameTo(staging.resolve("original.wcp.xz")))
            val key = "${version}_${revision}_$hash"
            val installed = parent.resolve(key)
            synchronized(this) {
                if (!installed.isDirectory) {
                    check(staging.renameTo(installed)) { "Не удалось сохранить версию FEXCore" }
                }
                val active = selection(context)
                val update = active.startWrite()
                try {
                    update.write(key.toByteArray(Charsets.UTF_8))
                    active.finishWrite(update)
                } catch (e: Exception) {
                    active.failWrite(update)
                    throw e
                }
            }
            return "$version ($revision)"
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun compression(archive: File): String {
        val magic = ByteArray(6)
        DataInputStream(archive.inputStream()).use { it.readFully(magic) }
        return when {
            magic.copyOf(4).contentEquals(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte())) -> "zstd"
            magic.contentEquals(byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0)) -> "xz"
            else -> error("Ожидался архив .wcp или .wcp.xz")
        }
    }

    private fun entries(archive: File, visit: (TarArchiveInputStream, TarArchiveEntry) -> Unit) {
        val mode = compression(archive)
        archive.inputStream().buffered().use { input ->
            val data = if (mode == "xz") XZInputStream(input, 64 * 1024)
                else ZstdInputStreamNoFinalizer(input).setLongMax(27)
            TarArchiveInputStream(LimitedInput(data, maxUnpacked)).use { tar ->
                var count = 0
                while (true) {
                    val entry = tar.nextEntry ?: break
                    require(++count <= 16_384 && entry.isCheckSumOK) { "Повреждённый или слишком большой TAR-архив" }
                    visit(tar, entry)
                }
            }
        }
    }

    private fun readProfile(archive: File): String {
        var profile: String? = null
        entries(archive) { tar, entry ->
            if (entry.name.removePrefix("./") == "profile.json") {
                require(profile == null && regular(entry) && entry.size in 1..maxProfile) { "Некорректный profile.json" }
                profile = tar.readBytes().toString(Charsets.UTF_8)
            }
        }
        return requireNotNull(profile) { "В архиве отсутствует profile.json" }
    }

    private fun extractDlls(archive: File, dir: File, mappings: Map<String, String>) {
        val found = mutableSetOf<String>()
        entries(archive) { tar, entry ->
            if (entry.name != "./" && entry.name != ".") {
                val path = safePath(entry.name.trimEnd('/'))
                require(entry.isDirectory || regular(entry)) { "Недопустимый тип записи в FEXCore" }
                mappings[path]?.let { name ->
                    require(found.add(name) && regular(entry) && entry.size in 512..maxDll) { "Некорректный размер или тип $name" }
                    val file = dir.resolve(name)
                    file.outputStream().use { out ->
                        require(tar.copyTo(out) == entry.size) { "Неполная DLL $name" }
                        out.fd.sync()
                    }
                    requirePe(file, dlls.getValue(name))
                }
            }
        }
        require(found == dlls.keys) { "В архиве отсутствует одна из DLL FEXCore" }
    }

    private fun regular(entry: TarArchiveEntry) = !entry.isSparse &&
        (entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM)

    private fun safePath(raw: String): String {
        val path = raw.removePrefix("./")
        require(path.length in 1..256 && '\\' !in path && ':' !in path &&
            path.none { it.isISOControl() } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Недопустимый путь в WCP"
        }
        return path
    }

    private fun requirePe(file: File, machine: Int) = RandomAccessFile(file, "r").use { pe ->
        val message = "Несовместимая PE DLL ${file.name}"
        require(pe.readUnsignedShort() == 0x4d5a) { message }
        pe.seek(60)
        val offset = Integer.reverseBytes(pe.readInt()).toLong()
        require(offset in 64..pe.length() - 26) { message }
        pe.seek(offset)
        require(pe.readInt() == 0x50450000) { message }
        val actualMachine = shortLe(pe)
        val hybrid = machine == 0xa641 && actualMachine == 0x8664
        require(actualMachine == machine || hybrid) { message }
        val sections = shortLe(pe)
        pe.seek(offset + 20)
        val optionalSize = shortLe(pe)
        val characteristics = shortLe(pe)
        val sectionTable = offset + 24 + optionalSize
        require(sections in 1..96 && optionalSize >= 120 && sectionTable + 40L * sections <= pe.length() &&
            characteristics and 0x2002 == 0x2002 && shortLe(pe) == 0x20b) { message }

        pe.seek(offset + 24 + 108)
        require(intLe(pe) > 0) { message }
        val exports = intLe(pe)
        require(exports > 0 && intLe(pe) >= 40) { message }
        val ranges = List(sections) { section ->
            pe.seek(sectionTable + 40L * section + 12)
            val rva = intLe(pe)
            val size = intLe(pe)
            val start = intLe(pe)
            require(start + size <= pe.length()) { message }
            Triple(rva, size, start)
        }
        fun sectionOffset(rva: Long, size: Long): Long {
            val (base, length, start) = requireNotNull(ranges.firstOrNull { (base, length) ->
                rva >= base && rva + size <= base + length
            }) { message }
            return start + rva - base
        }
        if (hybrid) {
            // ARM64EC PE files can have an AMD64 COFF header and CHPE load metadata.
            require(optionalSize >= 200) { message }
            pe.seek(offset + 24 + 24)
            val imageBase = java.lang.Long.reverseBytes(pe.readLong())
            pe.seek(offset + 24 + 108)
            require(intLe(pe) >= 11) { message }
            pe.seek(offset + 24 + 112 + 10 * 8)
            val loadConfig = intLe(pe)
            require(loadConfig > 0 && intLe(pe) >= 208) { message }
            val configOffset = sectionOffset(loadConfig, 208)
            pe.seek(configOffset)
            require(intLe(pe) >= 208) { message }
            pe.seek(configOffset + 200)
            val chpePointer = java.lang.Long.reverseBytes(pe.readLong())
            require(imageBase > 0 && chpePointer > imageBase && chpePointer - imageBase <= 0xffff_ffffL) { message }
            sectionOffset(chpePointer - imageBase, 8)
        }
        pe.seek(sectionOffset(exports, 40) + 24)
        val nameCount = intLe(pe)
        pe.skipBytes(4)
        require(nameCount in 1..1024) { message }
        val names = sectionOffset(intLe(pe), 4 * nameCount)
        val required = if (machine == 0xa641) mutableSetOf("ProcessInit", "BeginSimulation")
            else mutableSetOf("BTCpuProcessInit", "BTCpuSimulate")
        for (index in 0 until nameCount) {
            pe.seek(names + 4 * index)
            val nameRva = intLe(pe)
            val name = buildString {
                for (byte in 0L until 128L) {
                    pe.seek(sectionOffset(nameRva + byte, 1))
                    val code = pe.readUnsignedByte()
                    if (code == 0) break
                    append(code.toChar())
                }
            }
            required.remove(name)
            if (required.isEmpty()) break
        }
        require(required.isEmpty()) { message }
    }

    private fun shortLe(file: RandomAccessFile): Int = file.readUnsignedByte() or (file.readUnsignedByte() shl 8)
    private fun intLe(file: RandomAccessFile): Long = Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()))

    private class LimitedInput(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        private fun consumed(count: Long) {
            if (count > 0) remaining -= count
            require(remaining >= 0) { "Превышен допустимый размер WCP" }
        }

        override fun read(): Int = super.read().also { if (it != -1) consumed(1) }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt()).also { consumed(it.toLong()) }

        override fun skip(count: Long): Long = super.skip(minOf(count, remaining + 1)).also(::consumed)
    }
}
