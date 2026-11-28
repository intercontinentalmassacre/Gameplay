package app.gamenative.localgames

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal object BuranPrivatePrefixFiles {
    fun copySeed(source: Path, destination: Path) {
        copyMissing(source, destination, source, source.resolve("dosdevices"))
    }

    private fun copyMissing(source: Path, destination: Path, root: Path, dosdevices: Path) {
        if (source == dosdevices) return
        if (Files.isDirectory(source, NOFOLLOW_LINKS)) {
            if (!Files.exists(destination, NOFOLLOW_LINKS)) Files.createDirectory(destination)
            if (!Files.isDirectory(destination, NOFOLLOW_LINKS)) return
            Files.newDirectoryStream(source).use { children ->
                for (child in children) {
                    val name = child.fileName.toString()
                    copyMissing(source.resolve(name), destination.resolve(name), root, dosdevices)
                }
            }
        } else if (!Files.exists(destination, NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(source)) {
                val target = Files.readSymbolicLink(source)
                if (!target.isAbsolute && source.parent.resolve(target).normalize().startsWith(root)) {
                    Files.createSymbolicLink(destination, target)
                }
            } else if (Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                val pending = Files.createTempFile(destination.parent, ".buran-prefix-", ".tmp")
                try {
                    Files.copy(source, pending, REPLACE_EXISTING)
                    Files.move(pending, destination, ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(pending)
                }
            }
        }
    }

    /** Rebuilds Wine's drive aliases without opening the old links' targets. */
    fun setPrivateDrives(prefix: File) {
        val driveC = prefix.resolve("drive_c").toPath()
        require(Files.isDirectory(prefix.toPath(), NOFOLLOW_LINKS) && Files.isDirectory(driveC, NOFOLLOW_LINKS))
        val devices = prefix.resolve("dosdevices").absoluteFile
        val root = devices.toPath()
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS))

        val mappings = mapOf("c:" to Paths.get("../drive_c"), "z:" to Paths.get("/"))
        Files.newDirectoryStream(root).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val path = File(devices, name).toPath()
                if (name !in mappings && Files.isSymbolicLink(path)) Files.delete(path)
            }
        }
        for ((name, target) in mappings) {
            val path = File(devices, name).toPath()
            if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == target) continue
            require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            val pending = Files.createTempFile(root, ".buran-drive-", ".tmp")
            try {
                Files.delete(pending)
                Files.createSymbolicLink(pending, target)
                Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(pending)
            }
        }
    }

    @Synchronized
    fun replaceManagedLink(link: File, destination: File) {
        val path = link.toPath()
        val target = destination.canonicalFile.toPath()
        val symbolic = Files.isSymbolicLink(path)
        if (symbolic && Files.readSymbolicLink(path) == target) return
        require(symbolic || !Files.exists(path, NOFOLLOW_LINKS))
        val pending = Files.createTempFile(path.parent, ".buran-link-", ".tmp")
        try {
            Files.delete(pending)
            Files.createSymbolicLink(pending, target)
            Files.move(pending, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    @Synchronized
    fun removeManagedLink(link: File, expectedDestination: File) {
        val path = link.toPath()
        if (Files.isSymbolicLink(path) && Files.readSymbolicLink(path) == expectedDestination.toPath()) {
            Files.deleteIfExists(path)
        }
    }

    @Synchronized
    fun linkSteamHostUserdata(steamRoot: File, account: Long, appId: Int, sharedRemote: File) {
        require(account in 1..0xffffffffL && appId > 0)
        val shared = sharedRemote.canonicalFile
        require(shared == sharedRemote.absoluteFile && shared.isDirectory && shared.name == "remote" &&
            shared.parentFile?.name == appId.toString() && shared.parentFile?.parentFile?.name == account.toString())
        val title = steamRoot.canonicalFile.resolve("userdata/$account/$appId")
        val parent = checkNotNull(title.parentFile)
        require(parent.canonicalFile == parent && (parent.isDirectory || parent.mkdirs()))
        val path = title.toPath()
        val sharedTitle = checkNotNull(shared.parentFile)
        if (Files.isSymbolicLink(path)) {
            require(title.canonicalFile == sharedTitle)
            return
        }
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            replaceManagedLink(title, sharedTitle)
            return
        }
        require(Files.isDirectory(path, NOFOLLOW_LINKS))

        // Preserve the previous host tree and seed only files missing from the shared saves.
        copyMissingSteamSaves(title.resolve("remote").toPath(), shared.toPath())
        val backup = Files.createTempDirectory(parent.toPath(), ".buran-legacy-$appId-")
        val previous = backup.resolve(appId.toString())
        Files.move(path, previous, ATOMIC_MOVE)
        try {
            replaceManagedLink(title, sharedTitle)
        } catch (error: Exception) {
            runCatching { Files.move(previous, path, ATOMIC_MOVE) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun copyMissingSteamSaves(source: Path, destination: Path) {
        if (!Files.isDirectory(source, NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(source).use { files ->
            for (file in files) {
                val target = destination.resolve(file.fileName)
                if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                    if (!Files.exists(target, NOFOLLOW_LINKS)) Files.createDirectory(target)
                    if (Files.isDirectory(target, NOFOLLOW_LINKS)) copyMissingSteamSaves(file, target)
                } else if (Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.exists(target, NOFOLLOW_LINKS)) {
                    val pending = Files.createTempFile(destination, ".buran-remote-", ".tmp")
                    try {
                        Files.copy(file, pending, REPLACE_EXISTING)
                        try {
                            Files.createLink(target, pending)
                        } catch (_: java.nio.file.FileAlreadyExistsException) {
                            // A save appeared in the shared directory while this file was being copied.
                        }
                    } finally {
                        Files.deleteIfExists(pending)
                    }
                }
            }
        }
    }
}
