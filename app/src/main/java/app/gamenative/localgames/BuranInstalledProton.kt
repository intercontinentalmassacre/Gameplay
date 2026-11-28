package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.gamenative.R
import app.gamenative.utils.downloader.ContainerFilesDownloader
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.contents.ContentsManager
import com.winlator.core.WineInfo
import com.winlator.core.DefaultVersion
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

internal object BuranInstalledProton {
    const val STEAM_CLIENT_PROTON = "proton-10.0-arm64ec-2"

    fun configuredVersion(container: Container?): String? =
        container?.takeIf { it.containerVariant.equals(Container.BIONIC, true) }
            ?.wineVersion?.takeIf { it.startsWith("proton-", true) && "arm64ec" in it.lowercase() }

    fun resolve(context: Context, version: String): File? {
        val contents = ContentsManager(context).also { it.syncContents() }
        val info = WineInfo.fromIdentifier(context, contents, version)
        if (!info.isArm64EC) return null
        val configured = info.path?.takeIf { it.isNotBlank() }?.let(::File)
        val shared = context.filesDir.resolve("imagefs_shared/proton")
        val knownShared = when {
            version == STEAM_CLIENT_PROTON -> shared.resolve("proton-10.0-arm64ec")
            version.matches(Regex("proton-[0-9.]+(?:-[0-9]+)?-arm64ec(?:-[0-9]+)?")) ->
                shared.resolve(version)
            else -> null
        }
        val files = context.filesDir.canonicalFile.toPath()
        return listOfNotNull(configured, knownShared).asSequence().map { it.canonicalFile }.firstOrNull { wine ->
            wine.toPath().startsWith(files) &&
                isAndroidArm64Executable(wine.resolve("bin/wine")) &&
                isAndroidArm64Executable(wine.resolve("bin/wineserver")) &&
                wine.resolve("lib/wine/aarch64-windows").isDirectory &&
                wine.resolve("lib/wine/i386-windows").isDirectory
        }
    }

    fun attach(context: Context, appId: String, container: Container?, intent: Intent) {
        val version = configuredVersion(container)
            ?: error(context.getString(R.string.buran_proton_incompatible))
        val wine = resolve(context, version)
            ?: error(context.getString(R.string.buran_proton_not_installed, version))
        val fex = fexSystem32(context, container)
            ?: error(context.getString(R.string.buran_proton_fex_unavailable))
        val key = MessageDigest.getInstance("SHA-256").digest("$appId\u0000$version".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
        val prefix = context.filesDir.resolve("buran-installed-proton/$key/.wine")
        preparePrefix(context, wine, version, prefix, intent)
        configureBuranWineDesktop(prefix, intent.getBooleanExtra("wineDesktop", false))
        linkSteamSaves(prefix, intent)
        if (intent.getIntExtra("steamAppId", 0) > 0) BuranPhysXDependencies.reuse(container, prefix)
        BuranGraphicsPackages.attachInstalledProtonFallback(context, intent)
        val runtime = Bundle().apply {
            putBoolean("steamClient", false)
            putString("wineRoot", wine.path)
            putString("prefix", prefix.path)
            putString("fexSystem32", fex.path)
            putStringArrayList("fexEnvironment", BuranCpuPackages.fexEnvironment(context, container))
            wowBox64(context, container)?.let { putString("wowBox64", it.path) }
            intent.getBundleExtra("buranGraphics")?.let { putBundle("buranGraphics", it) }
        }
        intent.putExtra("steamBionicRuntime", runtime)
    }

    fun fexSystem32(context: Context, container: Container?): File? {
        val image = ImageFs.find(context)
        val gameSystem32 = container?.rootDir?.resolve(".wine/drive_c/windows/system32")
        val version = container?.getFEXCoreVersion().orEmpty()
        val selected = listOfNotNull(
            BuranCpuPackages.fexSystem32(context, container),
            gameSystem32?.takeIf { container?.getExtra("fexcoreVersion") == version },
            context.filesDir.resolve("contents/fexcore").resolve(version).resolve("system32").takeIf {
                version.matches(Regex("[A-Za-z0-9._+~-]+")) && version !in setOf(".", "..")
            },
        )
        val defaults = listOfNotNull(
            gameSystem32,
            image.rootDir.resolve("${ImageFs.WINEPREFIX}/drive_c/windows/system32"),
            context.filesDir.resolve("contents/fexcore/2611-gn/system32"),
        )
        return (selected + defaults.takeIf { version.isBlank() || version == DefaultVersion.FEXCORE }.orEmpty()).firstOrNull {
            it.resolve("libarm64ecfex.dll").length() > 0 && it.resolve("libwow64fex.dll").length() > 0
        }
    }

    fun wowBox64(context: Context, container: Container?): File? {
        if (container == null || container.emulator.isNullOrBlank() ||
            container.emulator.equals(Container.DEFAULT_EMULATOR, ignoreCase = true)
        ) return null
        return BuranCpuPackages.wowBox64(context, container)
            ?: error(context.getString(R.string.buran_proton_wowbox_unavailable))
    }

    @Synchronized
    internal fun preparePrefix(context: Context, wine: File, version: String, prefix: File, intent: Intent) {
        val failure = context.getString(R.string.buran_proton_prefix_seed_unavailable, version)
        val completionMarker = ".buran-offline-seed-v2"
        check(!Files.isSymbolicLink(prefix.toPath())) { failure }
        if (prefix.resolve(completionMarker).isFile && hasBootFiles(prefix)) {
            BuranPrivatePrefixFiles.setPrivateDrives(prefix)
            return
        }
        val owner = checkNotNull(prefix.parentFile)
        check(owner.isDirectory || owner.mkdirs()) { failure }
        val staging = Files.createTempDirectory(owner.toPath(), ".prefix-seed-").toFile()
        try {
            val seeded = staging.resolve(".wine")
            val base = sequenceOf(wine.resolve("base_prefix/.wine"), wine.resolve("base_prefix"))
                .map { it.canonicalFile }
                .firstOrNull { it.toPath().startsWith(wine.toPath()) && hasBootFiles(it) }
            if (base != null) {
                BuranPrivatePrefixFiles.copySeed(base.toPath(), seeded.toPath())
            } else {
                val cache = context.filesDir.resolve(ContainerFilesDownloader.CONTAINER_FILES_CACHE_DIR)
                val cached = cache.resolve("${version}_container_pattern.tzst")
                var extracted = ContainerManager.stageInstalledArm64EcPrefix(wine, staging)
                if (!extracted && cached.isFile && cached.length() > 0) {
                    deleteTree(staging.toPath())
                    check(staging.mkdir()) { failure }
                    extracted = ContainerManager.extractPrivatePrefixFiles(TarCompressorUtils.Type.ZSTD, cached, staging) &&
                        ContainerManager.expandInstalledArm64EcPrefix(wine, staging)
                }
                if (!extracted && hasBootFiles(prefix)) {
                    BuranPrivatePrefixFiles.setPrivateDrives(prefix)
                    return
                }
                check(extracted) { failure }
            }
            check(seeded.canonicalFile.parentFile == staging.canonicalFile && hasBootFiles(seeded)) { failure }

            if (!Files.exists(prefix.toPath(), NOFOLLOW_LINKS)) {
                linkSteamSaves(seeded, intent, template = true)
                BuranPrivatePrefixFiles.setPrivateDrives(seeded)
                check(seeded.resolve(completionMarker).createNewFile()) { failure }
                Files.move(seeded.toPath(), prefix.toPath(), ATOMIC_MOVE)
            } else {
                // An earlier Wineboot may have written user files or registry state before failing.
                // Fill gaps from the template without replacing any existing save or following its links.
                BuranPrivatePrefixFiles.copySeed(seeded.toPath(), prefix.toPath())
                BuranPrivatePrefixFiles.setPrivateDrives(prefix)
            }
            check(hasBootFiles(prefix)) { failure }
            val marker = prefix.resolve(completionMarker)
            check(marker.isFile || marker.createNewFile()) { failure }
        } finally {
            deleteTree(staging.toPath())
        }
    }

    private fun hasBootFiles(prefix: File): Boolean =
        listOf("system.reg", "user.reg", "drive_c/windows/system32/kernelbase.dll", "drive_c/windows/syswow64/kernelbase.dll")
            .all { name -> prefix.resolve(name).let { it.isFile && it.length() > 0 } }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    internal fun linkSteamSaves(prefix: File, intent: Intent, template: Boolean = false) {
        fun link(relative: String, source: File) {
            if (!source.isDirectory) return
            val target = prefix.resolve(relative)
            check(target.parentFile.canonicalFile.toPath().startsWith(prefix.canonicalFile.toPath()))
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target.toPath()) && target.canonicalFile == source.canonicalFile) return
                // An existing local save should never disappear when a profile is linked.
                if (template) deleteTree(target.toPath()) else {
                    check(target.isDirectory && !Files.isSymbolicLink(target.toPath()) && target.list().orEmpty().isEmpty())
                    Files.delete(target.toPath())
                }
            }
            target.parentFile.mkdirs()
            Files.createSymbolicLink(target.toPath(), source.canonicalFile.toPath())
        }
        intent.getStringExtra("steamUserDirectory")?.let { path ->
            for (folder in listOf("AppData/Local", "AppData/LocalLow", "AppData/Roaming", "Documents", "Saved Games")) {
                link("drive_c/users/${ImageFs.USER}/$folder", File(path, folder))
            }
        }
        intent.getStringExtra("steamRemoteDirectory")?.let { path ->
            val remote = File(path)
            val app = remote.parentFile?.name ?: return@let
            val account = remote.parentFile?.parentFile?.name ?: return@let
            if (app.toIntOrNull() == intent.getIntExtra("steamAppId", 0) &&
                (account.toLongOrNull() ?: 0) > 0
            ) {
                link("drive_c/Program Files (x86)/Steam/userdata/$account/$app/remote", remote)
            }
        }
    }

    /** Check the loader rather than trusting a file extension or a pack's display name. */
    private fun isAndroidArm64Executable(file: File): Boolean {
        if (!file.isFile || !file.canExecute()) return false
        return runCatching {
            RandomAccessFile(file, "r").use { executable ->
                fun read(offset: Long, size: Int): ByteBuffer {
                    executable.seek(offset)
                    val bytes = ByteArray(size)
                    executable.readFully(bytes)
                    return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                }
                val elf = read(0, 64)
                if (elf.getInt(0) != 0x464c457f || elf.get(4).toInt() != 2 ||
                    elf.get(5).toInt() != 1 || elf.getShort(18).toInt() != 183
                ) return false
                val programHeaderOffset = elf.getLong(32)
                val entrySize = elf.getShort(54).toInt() and 0xffff
                val entryCount = elf.getShort(56).toInt() and 0xffff
                if (programHeaderOffset < 64 || entrySize < 56 || entryCount !in 1..512 ||
                    programHeaderOffset > executable.length() - entrySize.toLong() * entryCount
                ) return false
                for (index in 0 until entryCount) {
                    val header = read(programHeaderOffset + entrySize.toLong() * index, 56)
                    if (header.getInt(0) != 3) continue
                    val size = header.getLong(32)
                    if (size !in 1..256) return false
                    val interpreter = String(read(header.getLong(8), size.toInt()).array()).trimEnd('\u0000')
                    return interpreter == "/system/bin/linker64" || interpreter == "/apex/com.android.runtime/bin/linker64"
                }
                false
            }
        }.getOrDefault(false)
    }
}
