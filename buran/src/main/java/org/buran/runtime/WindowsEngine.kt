package org.buran.runtime

import android.content.Context
import android.os.Build
import java.io.File

internal enum class WindowsEngine(val title: String, val bits: Int) {
    FEX("FEXCore · Windows x86-64", 64),
    BOX64("Box64 · Windows x86-64", 64),
    BOX86("Box86 · Windows x86", 32);

    override fun toString() = title

    fun stop(process: Process) {
        if (this == FEX) process.destroyForcibly() else BoxRuntime.stop(process)
    }

    fun console(context: Context) =
        if (this == FEX) WindowsProbe.run(context) else BoxRuntime.run(context, bits)

    fun window(
        context: Context, display: Map<String, String>, game: InstalledGame? = null, desktop: Boolean = false,
        requiresMono: Boolean = false, dxvk: Boolean = false,
        gameArguments: List<String> = emptyList(),
        external: ExternalWindowsLaunch? = null, desktopExit: File? = null,
        containerState: File? = null,
        cpu: BuranCpuSelection = BuranCpuSelection(),
        installedDxvk: DxvkComponents.Installed? = null,
        dependencies: SteamInstallDependencies? = null,
        dependencyProgress: File? = null,
        onDependencyInstalling: () -> Unit = {},
    ): Process {
        require(game == null || external == null) { "Укажите один источник установки игры" }
        val launchGame = external?.game ?: game
        validateGameArguments(gameArguments, launchGame != null)
        require(desktopExit == null || launchGame != null)
        require(launchGame == null || launchGame.bits == bits) { "Для этого EXE нужен транслятор ${launchGame?.bits} бит" }
        require(!requiresMono || this == BOX64 && launchGame != null) { "Для Wine Mono нужен запуск игры через Box64" }
        require(!dxvk || this == BOX64 && launchGame != null) { "Для DXVK нужен запуск игры x86-64 через Box64" }
        return if (this == FEX) {
            WindowsProbe.startWindow(context, display, launchGame, desktop, gameArguments, external, desktopExit,
                containerState, cpu.fexSystem32, dependencies, onDependencyInstalling)
        } else {
            BoxRuntime.startWindow(context, bits, display, launchGame, desktop, requiresMono, dxvk, gameArguments,
                external, desktopExit, containerState, box64Package = cpu.box64, installedDxvk = installedDxvk,
                dependencies = dependencies, dependencyProgress = dependencyProgress)
        }
    }

    companion object {
        fun available() = entries.filter {
            it != BOX86 || Build.SUPPORTED_32_BIT_ABIS.contains("armeabi-v7a")
        }

        fun selected(context: Context): WindowsEngine {
            val saved = context.buranPreferences("runtime", Context.MODE_PRIVATE).getString("engine", null)
            return available().find { it.name == saved } ?: FEX
        }
    }

    fun select(context: Context) =
        context.buranPreferences("runtime", Context.MODE_PRIVATE).edit().putString("engine", name).apply()
}

internal fun validateGameArguments(arguments: List<String>, hasGame: Boolean) {
    require(arguments.isEmpty() || hasGame) { "Аргументы требуют запуска игры" }
    require(arguments.none { '\u0000' in it }) { "Аргументы игры не могут содержать NUL" }
}

internal fun wineDesktopCommand(executable: String?, arguments: List<String>, helper: String? = null) =
    // Wine quotes argv for Explorer, which forwards the command line after /desktop to CreateProcessW.
    listOfNotNull("explorer.exe", "/desktop=shell,1280x720", helper, executable ?: "winefile.exe") + arguments
