package org.buran.runtime

import android.app.Activity
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal data class WineContainer(val id: String, val name: String, val engine: WindowsEngine, val legacy: Boolean = false)

internal object Containers {
    private val gameToken = Regex("[a-f0-9]{32}")
    private val oldContainer = Regex("legacy-(fex|box64|box86)-(game-state|external-game-state)-([a-f0-9]{32})")
    private val newContainer = Regex("[a-f0-9-]{36}")

    private fun WindowsEngine.backend() = name.lowercase()
    private fun root(context: Context) = context.buranFilesDir.resolve("wine-containers")

    private fun gameRoot(context: Context, gameId: String, external: Boolean): File {
        require(gameToken.matches(gameId)) { "Недопустимый код игры" }
        val directory = if (external) "external-game-state" else "game-state"
        return context.buranFilesDir.resolve("$directory/$gameId")
    }

    private fun assignment(root: File, engine: WindowsEngine) = root.resolve(".container-${engine.backend()}")

    private fun default(engine: WindowsEngine) = WineContainer("default-${engine.backend()}", "Общий", engine)

    private fun legacyState(root: File, engine: WindowsEngine): Boolean = when (engine) {
        WindowsEngine.FEX -> root.resolve("fex/drive_c").isDirectory
        WindowsEngine.BOX86 -> root.resolve("box86/prefix").isDirectory
        WindowsEngine.BOX64 -> listOf("box64", "box64-dxvk").any { root.resolve("$it/prefix").isDirectory }
    }

    private fun legacy(context: Context, root: File, engine: WindowsEngine): WineContainer {
        val game = if (root.parentFile?.name == "game-state") {
            gamesDirectory(context).resolve(".library/${root.name}").takeIf(File::isFile)
                ?.readText()?.substringAfter('\n', "")?.substringAfterLast('/')?.ifBlank { null }
        } else null
        val kind = if (root.parentFile?.name == "external-game-state") "Внешняя установка" else "Игра"
        val label = game ?: "$kind ${root.name.take(8)}"
        return WineContainer("legacy-${engine.backend()}-${root.parentFile!!.name}-${root.name}", "$label · прежний", engine, legacy = true)
    }

    fun list(context: Context): List<WineContainer> = WindowsEngine.available().flatMap { list(context, it) }

    fun list(context: Context, engine: WindowsEngine): List<WineContainer> {
        val named = root(context).listFiles().orEmpty().mapNotNull { named(it) }.filter { it.engine == engine }
        val old = listOf("game-state", "external-game-state").flatMap { parent ->
            context.buranFilesDir.resolve(parent).listFiles().orEmpty().filter {
                gameToken.matches(it.name) && legacyState(it, engine)
            }.map { legacy(context, it, engine) }
        }
        return listOf(default(engine)) + (named + old).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    private fun named(directory: File): WineContainer? {
        if (!newContainer.matches(directory.name)) return null
        val fields = directory.resolve("name").takeIf(File::isFile)?.readLines() ?: return null
        val engine = WindowsEngine.entries.find { it.name == fields.firstOrNull() } ?: return null
        val name = fields.getOrNull(1) ?: return null
        return WineContainer(directory.name, name, engine)
    }

    fun find(context: Context, id: String): WineContainer {
        WindowsEngine.entries.firstOrNull { default(it).id == id }?.let { return default(it) }
        if (newContainer.matches(id)) named(root(context).resolve(id))?.let { return it }
        oldContainer.matchEntire(id)?.destructured?.let { (backend, parent, gameId) ->
            val engine = WindowsEngine.entries.first { it.backend() == backend }
            val path = context.buranFilesDir.resolve("$parent/$gameId")
            if (legacyState(path, engine)) return legacy(context, path, engine)
        }
        error("Контейнер недоступен: $id")
    }

    fun selected(context: Context, gameId: String, engine: WindowsEngine, external: Boolean = false): WineContainer {
        val path = gameRoot(context, gameId, external)
        val saved = assignment(path, engine).takeIf(File::isFile)?.readText()
        return if (saved != null) find(context, saved).also { check(it.engine == engine) }
            else if (legacyState(path, engine)) legacy(context, path, engine)
            else default(engine)
    }

    fun assign(context: Context, gameId: String, containerId: String, external: Boolean = false) {
        val container = find(context, containerId)
        write(assignment(gameRoot(context, gameId, external), container.engine), container.id)
    }

    fun create(context: Context, engine: WindowsEngine, name: String): WineContainer {
        require(engine in WindowsEngine.available()) { "Движок недоступен" }
        val id = UUID.randomUUID().toString()
        return saveName(context, WineContainer(id, name, engine), name)
    }

    fun rename(context: Context, containerId: String, name: String): WineContainer {
        val container = find(context, containerId)
        require(newContainer.matches(container.id)) { "Переименовать можно созданный контейнер" }
        return saveName(context, container, name)
    }

    private fun saveName(context: Context, container: WineContainer, name: String): WineContainer {
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 80 && label.none(Char::isISOControl)) {
            "Введите имя контейнера длиной до 80 символов"
        }
        write(root(context).resolve("${container.id}/name"), "${container.engine.name}\n$label")
        return container.copy(name = label)
    }

    private fun legacyRoot(context: Context, container: WineContainer): File {
        val match = checkNotNull(oldContainer.matchEntire(container.id))
        return context.buranFilesDir.resolve("${match.groupValues[2]}/${match.groupValues[3]}")
    }

    fun directory(context: Context, container: WineContainer): File {
        if (!container.legacy) return root(context).resolve("${container.id}/state")
        val path = legacyRoot(context, container)
        return if (container.engine == WindowsEngine.BOX64) Box64State.directory(path)
            else path.resolve(container.engine.backend())
    }

    fun prepare(activity: Activity, container: WineContainer, launch: () -> Unit) {
        if (container.legacy && container.engine == WindowsEngine.BOX64) {
            Box64State.choose(activity, legacyRoot(activity, container), launch)
        } else launch()
    }

    internal fun write(file: File, value: String) {
        val parent = requireNotNull(file.parentFile).toPath()
        Files.createDirectories(parent)
        val pending = Files.createTempFile(parent, ".pending-", null)
        try {
            FileOutputStream(pending.toFile()).use {
                it.write(value.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(pending, file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }
}
