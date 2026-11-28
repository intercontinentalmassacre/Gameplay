package org.buran.runtime

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Identifies the x86 VC80 CRT from the Microsoft VC++ 2005 RTM redistributable. */
internal object LegacyVc80Runtime {
    private const val assembly = "x86_microsoft.vc80.crt_1fc8b3b9a1e18e3b_8.0.50727.42"
    private val dlls = listOf(
        Fingerprint("msvcr80.dll", 626688, "120CD25F5D6002FFD9069CF9550BC16C682BCD3323053B95146E7CD3BA2215AC"),
        Fingerprint("msvcp80.dll", 548864, "9FC2E85BA84CF0459AAB0DC2EFAC734AD7B5B4C99BA19871FE8F6E35D0191838"),
        Fingerprint("msvcm80.dll", 479232, "C75E2F91A7B2032D3757EEAC12502112381E0CB6F0E6E308ADC74AC30C8A7EC7"),
    )

    fun isInstalled(prefix: File, installer: File): Boolean = runCatching {
        if (!matchesInstaller(installer)) return false
        val root = prefix.resolve("drive_c/windows/winsxs").canonicalFile
        if (!root.toPath().startsWith(prefix.canonicalFile.toPath())) return false
        val manifests = root.resolve("manifests").canonicalFile
        if (manifests.parentFile != root) return false
        root.listFiles().orEmpty().any { directory ->
            directory.isDirectory && directory.canonicalFile.parentFile == root && matchesAssemblyName(directory.name) &&
                Fingerprint("${directory.name}.manifest", 1868,
                    "B19C81F6BBBD4A0F0A1C50283D83BD4CBE6BEB596FB0A0B9181510F0B31FA787").matches(manifests) &&
                dlls.all { it.matches(directory) && isX86Dll(directory.resolve(it.name)) }
        }
    }.getOrDefault(false)

    internal fun matchesInstaller(installer: File): Boolean =
        Fingerprint(installer.name, 2686232, "3453E87AA523E54F07337ABE75C047B1E827C02B105A92028DC55DEDE9F97FFB")
            .matches(installer.absoluteFile.parentFile.canonicalFile)

    internal fun matchesAssemblyName(name: String): Boolean =
        name.equals("${assembly}_none_deadbeef", ignoreCase = true) ||
            name.equals("${assembly}_x-ww_0de06acd", ignoreCase = true)

    private fun isX86Dll(file: File): Boolean = RandomAccessFile(file, "r").use {
        if (it.readUnsignedShort() != 0x4d5a) return false
        it.seek(60)
        val pe = Integer.reverseBytes(it.readInt()).toLong()
        if (pe !in 64..it.length() - 24) return false
        it.seek(pe)
        if (it.readInt() != 0x50450000 || it.readUnsignedShort() != 0x4c01) return false
        it.seek(pe + 22)
        it.readUnsignedByte()
        it.readUnsignedByte() and 0x20 != 0 // IMAGE_FILE_DLL
    }

    private data class Fingerprint(val name: String, val size: Long, val sha256: String) {
        fun matches(directory: File): Boolean {
            val file = directory.resolve(name)
            if (!file.isFile || file.length() != size || file.canonicalFile.parentFile != directory) return false
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(bytes)
                    if (read == -1) break
                    digest.update(bytes, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02X".format(it) } == sha256
        }
    }
}
