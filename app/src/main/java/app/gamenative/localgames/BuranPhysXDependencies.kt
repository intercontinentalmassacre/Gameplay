package app.gamenative.localgames

import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object BuranPhysXDependencies {
    private const val runtime = "Program Files (x86)/AGEIA Technologies"
    private const val loader = "windows/syswow64/PhysXLoader.dll"
    private const val registryPathValue = """"PhysXCore Path"="C:\\Program Files (x86)\\AGEIA Technologies""""
    private const val key = """Software\\Wow6432Node\\AGEIA Technologies"""

    /** Reuses a game's real x86 AGEIA installation and its matching machine registry metadata. */
    fun reuse(container: Container?, prefix: File): Boolean {
        val source = container?.rootDir?.resolve(".wine")?.canonicalFile ?: return false
        val target = prefix.canonicalFile
        if (source == target || !source.isDirectory || !target.isDirectory ||
            source.toPath().startsWith(target.toPath()) || target.toPath().startsWith(source.toPath())
        ) return false
        val sourceRegistry = source.resolve("system.reg")
        val targetRegistry = target.resolve("system.reg")
        if (!Files.isRegularFile(sourceRegistry.toPath(), NOFOLLOW_LINKS) ||
            !Files.isRegularFile(targetRegistry.toPath(), NOFOLLOW_LINKS)
        ) return false

        val sourceDrive = source.resolve("drive_c").canonicalFile
        if (!sourceDrive.toPath().startsWith(source.toPath())) return false
        val ageia = sourceDrive.resolve(runtime).canonicalFile
        val physx = sourceDrive.resolve(loader).canonicalFile
        if (!ageia.toPath().startsWith(sourceDrive.toPath()) || !physx.toPath().startsWith(sourceDrive.toPath()) ||
            !ageia.isDirectory || !physx.isFile || physx.length() == 0L
        ) return false
        if (physx.inputStream().use(WindowsExecutableInspector::inspect).kind != ExecutableKind.WINDOWS_32_PE) return false
        if (!hasEngines(ageia)) return false

        val current = targetRegistry.readText()
        val targetDrive = target.resolve("drive_c").canonicalFile
        check(targetDrive.toPath().startsWith(target.toPath()))
        val companions = physx.parentFile.listFiles().orEmpty().filter {
            it.name.startsWith("physx", ignoreCase = true) && it.extension.equals("dll", true) &&
                Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
        }
        // Keep an actual installscript result already registered in this persistent prefix.
        if (physXSections(current) != null && targetDrive.resolve(loader).isFile &&
            hasEngines(targetDrive.resolve(runtime))
        ) {
            // Repair prefixes seeded before the loader's companion DLLs were included.
            companions.forEach { dll ->
                val destination = targetDrive.resolve("windows/syswow64/${dll.name}")
                if (!destination.isFile) copyFile(dll.toPath(), destination, targetDrive)
            }
            return true
        }
        val merged = mergeRegistry(sourceRegistry.readText(), current) ?: return false
        Files.walk(ageia.toPath()).use { entries ->
            entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.forEach { file ->
                val relative = ageia.toPath().relativize(file).toString()
                copyFile(file, targetDrive.resolve("$runtime/$relative"), targetDrive)
            }
        }
        companions.forEach { dll ->
            copyFile(dll.toPath(), targetDrive.resolve("windows/syswow64/${dll.name}"), targetDrive)
        }
        if (merged != current) {
            replaceFile(targetRegistry.toPath()) { it.toFile().writeText(merged) }
        }
        return true
    }

    internal fun mergeRegistry(source: String, target: String): String? {
        val installedSections = physXSections(source) ?: return null
        val merged = StringBuilder(target)
        sections(target).filter { (name, _) -> isPhysX(name) }.asReversed().forEach { (_, range) ->
            merged.delete(range.first, range.last + 1)
        }
        return buildString {
            append(merged.toString().trimEnd())
            append("\n\n")
            installedSections.forEach { (_, range) ->
                append(source.substring(range).trimEnd())
                append("\n\n")
            }
        }
    }

    private fun physXSections(contents: String): List<Pair<String, IntRange>>? {
        val installed = sections(contents).filter { (name, _) -> isPhysX(name) }
        val root = installed.firstOrNull { it.first.equals(key, true) } ?: return null
        return installed.takeIf { contents.substring(root.second).lineSequence().any { it.equals(registryPathValue, true) } }
    }

    private fun hasEngines(ageia: File): Boolean = ageia.listFiles().orEmpty().any { engine ->
        Files.isDirectory(engine.toPath(), NOFOLLOW_LINKS) &&
            Files.isRegularFile(engine.resolve("PhysXCore.dll").toPath(), NOFOLLOW_LINKS)
    }

    private fun isPhysX(name: String) = name.equals(key, true) || name.startsWith("$key\\\\", true)

    private fun sections(contents: String): List<Pair<String, IntRange>> {
        val ranges = mutableListOf<Pair<String, IntRange>>()
        var start = 0
        var name: String? = null
        var offset = 0
        while (offset < contents.length) {
            val end = contents.indexOf('\n', offset).let { if (it < 0) contents.length else it }
            if (contents[offset] == '[') {
                val close = contents.lastIndexOf(']', end - 1)
                if (close > offset) {
                    name?.let { ranges.add(it to (start until offset)) }
                    name = contents.substring(offset + 1, close)
                    start = offset
                }
            }
            offset = end + 1
        }
        name?.let { ranges.add(it to (start until contents.length)) }
        return ranges
    }

    private fun copyFile(source: Path, destination: File, drive: File) {
        check(destination.parentFile.canonicalFile.toPath().startsWith(drive.toPath()))
        destination.parentFile.mkdirs()
        if (Files.isRegularFile(destination.toPath(), NOFOLLOW_LINKS) && destination.length() == Files.size(source) &&
            destination.lastModified() == Files.getLastModifiedTime(source).toMillis()
        ) return
        replaceFile(destination.toPath()) {
            Files.copy(source, it, REPLACE_EXISTING)
            Files.setLastModifiedTime(it, Files.getLastModifiedTime(source))
        }
    }

    private fun replaceFile(destination: Path, write: (Path) -> Unit) {
        val temporary = Files.createTempFile(destination.parent, ".buran-physx-", ".tmp")
        try {
            write(temporary)
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
