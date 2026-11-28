package app.gamenative.localgames

import android.content.Intent
import android.os.Bundle
import app.gamenative.service.SteamService
import app.gamenative.utils.installscript.InstallScriptExecutor
import app.gamenative.utils.installscript.InstallScriptParser
import com.winlator.container.Container
import java.io.File

internal object BuranSteamDependencies {
    /** Builds a plan from Steam's locally cached depots and the game's conventional script. */
    suspend fun attach(intent: Intent, container: Container?) {
        val appId = intent.getIntExtra("steamAppId", 0).takeIf { it > 0 } ?: return
        val game = intent.getStringExtra("gameDirectory")?.let(::File)?.canonicalFile
            ?.takeIf { it.isDirectory && !SteamService.isInstallRootOrAbove(it) } ?: return
        val language = container?.language.orEmpty().ifBlank { "english" }
        val shared = game.parentFile?.resolve("Steamworks Shared")?.canonicalFile?.takeIf {
            it.parentFile == game.parentFile && it.isDirectory
        }
        val steamApp = SteamService.getAppInfoOf(appId)
        val cached = SteamService.instance?.appInfoDao?.get(appId)
        val scripts = if (steamApp != null && cached != null) {
            InstallScriptExecutor.collectScripts(steamApp, cached, game, "A:", language, appId, shared)
        } else emptyList()
        val commonRedist = game.listFiles()?.firstOrNull {
            it.name.equals("_CommonRedist", ignoreCase = true) && it.isDirectory &&
                it.canonicalFile.parentFile == game
        }
        val conventional = sequenceOf(game).plus(
            commonRedist?.walkTopDown()?.maxDepth(3)?.onEnter {
                it.canonicalFile.toPath().startsWith(game.toPath()) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath())
            }?.filter(File::isDirectory) ?: emptySequence(),
        ).take(128).flatMap { directory ->
            directory.listFiles().orEmpty().asSequence()
                .filter { it.isFile && it.name.equals("installscript.vdf", ignoreCase = true) }
        }.filter { it.canonicalFile.toPath().startsWith(game.toPath()) && it.length() in 1..(1024L * 1024) }
            .filter { file -> scripts.none { it.sourcePath == file.canonicalPath } }
            .take(64).map { InstallScriptParser.parse(it.canonicalFile, "A:", language) }.toList()
        val plan = scripts + conventional
        if (plan.isEmpty()) return
        val executable = intent.getStringExtra("executable")?.let { resolveGameExecutable(game, it, ignoreCase = true) }
        val is64Bit = intent.hasExtra("steamBionicRuntime") || executable?.inputStream()?.use(WindowsExecutableInspector::inspect)
            ?.kind == ExecutableKind.WINDOWS_64_PE
        val dependencies = InstallScriptExecutor.localDependencies(plan, game, shared, is64Bit)
        val registry = InstallScriptExecutor.localRegistry(plan, language)
        if (dependencies.isEmpty() && registry.isEmpty()) return
        intent.putExtra("buranSteamDependencies", Bundle().apply {
            putParcelableArrayList("steps", ArrayList(dependencies.take(64).map { step ->
                Bundle().apply {
                    putString("executable", step.executable.path)
                    putStringArrayList("arguments", ArrayList(step.arguments))
                    putString("marker", step.marker)
                    putInt("markerValue", step.markerValue)
                }
            }))
            putParcelableArrayList("registry", ArrayList(registry.take(128).map { entry ->
                Bundle().apply {
                    putString("key", entry.key)
                    putString("name", entry.name)
                    putString("type", entry.type)
                    putString("value", entry.value)
                }
            }))
        })
    }
}
