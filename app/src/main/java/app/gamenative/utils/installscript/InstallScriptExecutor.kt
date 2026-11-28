package app.gamenative.utils.installscript

import app.gamenative.data.AppInfo
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import app.gamenative.utils.FileUtils
import com.winlator.container.Container
import com.winlator.core.ProcessHelper
import com.winlator.core.WineRegistryEditor
import `in`.dragonbra.javasteam.enums.EDepotFileFlag
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import java.util.EnumSet
import timber.log.Timber

object InstallScriptExecutor {

    private const val CLAIMED_OS_TYPE = "10"
    private const val EXIT_CODE_FILE = "installscript_exit"
    private const val STELLAR_BLADE_APP_ID = 3489700
    private const val PSPC_INSTALLER = "SB/Binaries/Win64/install_pspc_sdk_runtime.bat"
    private const val STEAMWORKS_SHARED = "C:/Program Files (x86)/Steam/steamapps/common/Steamworks Shared"

    data class RunProcessCommand(
        val executable: String,
        val hasRunKey: String?,
    )

    data class LocalDependency(
        val executable: File,
        val arguments: List<String>,
        val marker: String,
        val markerValue: Int,
    )

    data class LocalRegistryValue(val key: String, val name: String, val type: String, val value: String)

    fun localRegistry(scripts: List<InstallScript>, language: String): List<LocalRegistryValue> =
        scripts.flatMap { script ->
            script.registryActions.flatMap { action ->
                val hive = if (isHkcuKey(action.keyPath)) "HKCU" else "HKLM"
                val key = "$hive\\${stripHivePrefix(action.keyPath)}"
                val values = mergeWithLanguage(action, language)
                values.strings.map { (name, value) -> LocalRegistryValue(key, name, "REG_SZ", value) } +
                    values.dwords.map { (name, value) -> LocalRegistryValue(key, name, "REG_DWORD", value.toString()) }
            }
        }.distinctBy { it.key.lowercase() to it.name.lowercase() }

    /** Resolves local installers without the Winlator desktop wrapper or any download step. */
    fun localDependencies(
        scripts: List<InstallScript>,
        gameDir: File,
        sharedDir: File?,
        is64Bit: Boolean,
    ): List<LocalDependency> = scripts.flatMap { script ->
        script.runProcessActions.mapNotNull { action ->
            if (!matchesOS(action.requirementOS, is64Bit)) return@mapNotNull null
            val process = action.process.trim().removeSurrounding("\"").replace('\\', '/')
            val sharedPrefix = "$STEAMWORKS_SHARED/"
            val (root, relative) = when {
                process.startsWith("A:/", ignoreCase = true) -> gameDir to process.drop(3)
                process.startsWith(sharedPrefix, ignoreCase = true) -> {
                    val relative = process.drop(sharedPrefix.length)
                    if (sharedDir == null) {
                        Timber.tag("InstallScript").w("Required local Steamworks Shared installer is missing: %s", relative)
                        return@mapNotNull null
                    }
                    sharedDir to relative
                }
                ':' !in process && !process.startsWith('/') -> gameDir to process.removePrefix("./")
                else -> return@mapNotNull null
            }
            if (':' in relative || relative.split('/').any {
                    it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl)
                }
            ) {
                return@mapNotNull null
            }
            val arguments = ProcessHelper.splitCommand(action.command).toList()
            if (uninstallName.containsMatchIn(action.name) || uninstallName.containsMatchIn(relative.substringAfterLast('/')) ||
                arguments.any { it.isUninstallArgument() || it.contains("://") || it.any(Char::isISOControl) }
            ) return@mapNotNull null
            val executable = FileUtils.findFileCaseInsensitive(root, relative)?.canonicalFile ?: run {
                Timber.tag("InstallScript").w("Required local installer is missing: %s", relative)
                return@mapNotNull null
            }
            val extension = executable.extension.lowercase()
            if (!executable.isFile || !executable.toPath().startsWith(root.canonicalFile.toPath()) ||
                (extension !in setOf("exe", "msi") && !(extension == "bat" && isPspcInstaller(action)))
            ) return@mapNotNull null
            val marker = action.hasRunKey?.takeIf {
                '\\' in it && it.none(Char::isISOControl) && action.name.isNotBlank() &&
                    '\\' !in action.name && action.name.none(Char::isISOControl)
            }
                ?.let {
                    val hive = if (isHkcuKey(it)) "HKCU" else "HKLM"
                    "$hive\\${stripHivePrefix(it).trimEnd('\\')}\\${action.name}"
                }
                ?: "HKLM\\Software\\GameNative\\InstallScript\\${File(script.sourcePath).name.hashCode()}\\${action.name.hashCode()}"
            LocalDependency(executable, arguments, marker, maxOf(1, action.minimumHasRunValue))
        }
    }.distinctBy { it.marker }

    private val uninstallName = Regex("uninstall|^unins[0-9]", RegexOption.IGNORE_CASE)

    private fun String.isUninstallArgument(): Boolean {
        if (!startsWith('/') && !startsWith('-')) return false
        val option = trimStart('/', '-').substringBefore('=').lowercase()
        return option in setOf("u", "x", "uninstall", "remove", "revoke") || option.startsWith("x{")
    }

    fun collectScripts(
        steamApp: SteamApp,
        appInfo: AppInfo,
        gameDir: File,
        installDir: String,
        language: String,
        appId: Int,
        sharedDir: File? = null,
    ): List<InstallScript> {
        val appDirPath = SteamService.getAppDirPath(appId)
        val downloadedDepotIds = appInfo.downloadedDepots.toSet()
        val installedBranch = SteamService.getInstalledApp(appId)?.branch ?: "public"

        val scriptPaths = mutableSetOf<String>()

        for ((depotId, depot) in steamApp.depots) {
            if (depotId !in downloadedDepotIds) continue

            val mi = depot.manifests[installedBranch]
                ?: depot.encryptedManifests[installedBranch]
                ?: depot.manifests["public"]
                ?: continue

            val manifestFile = "$appDirPath/.DepotDownloader/${depotId}_${mi.gid}.manifest"
            val manifest = try {
                DepotManifest.loadFromFile(manifestFile)
            } catch (e: Exception) {
                Timber.tag("InstallScript").d("Could not load manifest for depot $depotId: ${e.message}")
                continue
            } ?: continue

            manifest.files?.forEach { fileData ->
                if (isInstallScript(fileData.flags)) {
                    val path = fileData.fileName.toString().replace('\\', '/')
                    scriptPaths.add(path)
                    Timber.tag("InstallScript").d("Found install script in depot $depotId: $path")
                }
            }
        }

        val root = gameDir.canonicalFile.toPath()
        val scripts = scriptPaths.mapNotNull { relativePath ->
            val scriptFile = File(gameDir, relativePath).canonicalFile
            if (scriptFile.isFile && scriptFile.toPath().startsWith(root)) {
                return@mapNotNull InstallScriptParser.parse(scriptFile, installDir, language)
            }
            if (sharedDir != null && relativePath.startsWith("_CommonRedist/", ignoreCase = true)) {
                val sharedRoot = sharedDir.canonicalFile.toPath()
                val sharedScript = FileUtils.findFileCaseInsensitive(sharedDir, relativePath)?.canonicalFile
                if (sharedScript != null && sharedScript.isFile && sharedScript.toPath().startsWith(sharedRoot)) {
                    return@mapNotNull InstallScriptParser.parse(sharedScript, STEAMWORKS_SHARED, language)
                }
            }
            Timber.tag("InstallScript").w("InstallScript file not found: %s", relativePath)
            null
        }
        if (appId != STELLAR_BLADE_APP_ID || scripts.any { script -> script.runProcessActions.any(::isPspcInstaller) }) {
            return scripts
        }
        // Some installs have the vendor script on disk without usable depot metadata.
        return scripts + listOfNotNull(stellarBladeSdkScript(gameDir, installDir, language))
    }

    private fun isPspcInstaller(action: RunProcessAction): Boolean =
        action.process.trim().removeSurrounding("\"").replace('\\', '/')
            .equals("A:/$PSPC_INSTALLER", ignoreCase = true) && action.command.trim().equals("quiet", ignoreCase = true)

    internal fun stellarBladeSdkScript(gameDir: File, installDir: String = "A:", language: String = "english"): InstallScript? {
        val installer = File(gameDir, PSPC_INSTALLER)
        val script = File(installer.parentFile, "installscript.vdf")
        val root = gameDir.canonicalFile.toPath()
        if (!installer.isFile || !script.isFile ||
            !installer.canonicalFile.toPath().startsWith(root) || !script.canonicalFile.toPath().startsWith(root)
        ) return null

        val parsed = InstallScriptParser.parse(script, installDir, language)
        val step = parsed.runProcessActions.firstOrNull(::isPspcInstaller) ?: return null
        return InstallScript(sourcePath = parsed.sourcePath, runProcessActions = listOf(step))
    }

    private fun isInstallScript(flags: Any): Boolean = when (flags) {
        is EnumSet<*> -> flags.contains(EDepotFileFlag.InstallScript)
        is Int -> (flags and EDepotFileFlag.InstallScript.code()) != 0
        is Long -> (flags and EDepotFileFlag.InstallScript.code().toLong()) != 0L
        else -> false
    }

    fun applyRegistryKeys(container: Container, scripts: List<InstallScript>, language: String) {
        val systemRegFile = File(container.rootDir, ".wine/system.reg")
        val userRegFile = File(container.rootDir, ".wine/user.reg")

        val systemActions = mutableListOf<Pair<String, RegistryValues>>()
        val userActions = mutableListOf<Pair<String, RegistryValues>>()

        for (script in scripts) {
            for (action in script.registryActions) {
                val mergedValues = mergeWithLanguage(action, language)
                val strippedKey = stripHivePrefix(action.keyPath)

                if (action.keyPath.startsWith("HKLM", ignoreCase = true) ||
                    action.keyPath.startsWith("HKEY_LOCAL_MACHINE", ignoreCase = true)
                ) {
                    systemActions.add(strippedKey to mergedValues)
                } else {
                    userActions.add(strippedKey to mergedValues)
                }
            }
        }

        if (systemActions.isNotEmpty() && systemRegFile.exists()) {
            writeRegistryValues(systemRegFile, systemActions)
        }
        if (userActions.isNotEmpty() && userRegFile.exists()) {
            writeRegistryValues(userRegFile, userActions)
        }
    }

    fun getRunProcessCommands(
        container: Container,
        scripts: List<InstallScript>,
        screenInfo: String,
        is64Bit: Boolean,
    ): List<RunProcessCommand> {
        val commands = mutableListOf<RunProcessCommand>()

        for (script in scripts) {
            for (action in script.runProcessActions) {
                if (!matchesOS(action.requirementOS, is64Bit)) continue

                val effectiveHasRunKey = action.hasRunKey
                    ?: "Software\\GameNative\\InstallScript\\${script.sourcePath.hashCode()}\\${action.name}"

                if (hasAlreadyRun(container, effectiveHasRunKey, action.minimumHasRunValue)) continue

                val quotedProcess = if (action.process.startsWith("\"") && action.process.endsWith("\"")) {
                    action.process
                } else {
                    "\"${action.process}\""
                }
                val cmdLine = if (isPspcInstaller(action)) {
                    // Run beside the SDK's MSI files and return to cmd to record the exit status.
                    "cd /d \"A:\\SB\\Binaries\\Win64\" && call $quotedProcess quiet"
                } else if (action.command.isNotEmpty()) {
                    "$quotedProcess ${action.command}"
                } else {
                    quotedProcess
                }
                val wrapped = wrapAsGuestExecutable(cmdLine, screenInfo)

                commands.add(RunProcessCommand(wrapped, effectiveHasRunKey))
            }
        }
        return commands
    }

    fun markRunProcessComplete(container: Container, hasRunKey: String) {
        val regFile = resolveRegFile(container, hasRunKey)
        if (!regFile.exists()) return
        try {
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                val keyPath = hasRunKey.substringBeforeLast("\\")
                val valueName = hasRunKey.substringAfterLast("\\")
                editor.setDwordValue(stripHivePrefix(keyPath), valueName, 1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to mark run process complete: $hasRunKey")
        }
    }

    internal fun mergeWithLanguage(action: RegistryAction, language: String): RegistryValues {
        val langOverride = action.languageOverrides[language.lowercase()]
            ?: return action.values
        return RegistryValues(
            strings = action.values.strings + langOverride.strings,
            dwords = action.values.dwords + langOverride.dwords,
        )
    }

    internal fun matchesOS(requirement: OSRequirement?, is64Bit: Boolean): Boolean {
        if (requirement == null) return true
        if (requirement.is64BitWindows != null && requirement.is64BitWindows != is64Bit) return false
        if (requirement.osType != null && requirement.osType != CLAIMED_OS_TYPE) return false
        return true
    }

    internal fun stripHivePrefix(keyPath: String): String {
        val prefixes = listOf("HKEY_LOCAL_MACHINE\\", "HKEY_CURRENT_USER\\", "HKLM\\", "HKCU\\")
        for (prefix in prefixes) {
            if (keyPath.startsWith(prefix, ignoreCase = true)) {
                return keyPath.substring(prefix.length)
            }
        }
        return keyPath
    }

    private fun isHkcuKey(keyPath: String): Boolean =
        keyPath.startsWith("HKCU", ignoreCase = true) ||
            keyPath.startsWith("HKEY_CURRENT_USER", ignoreCase = true)

    private fun resolveRegFile(container: Container, hasRunKey: String): File {
        val regName = if (isHkcuKey(hasRunKey)) ".wine/user.reg" else ".wine/system.reg"
        return File(container.rootDir, regName)
    }

    private fun hasAlreadyRun(container: Container, hasRunKey: String, minimumHasRunValue: Int): Boolean {
        val regFile = resolveRegFile(container, hasRunKey)
        if (!regFile.exists()) return false
        return try {
            WineRegistryEditor(regFile).use { editor ->
                val keyPath = hasRunKey.substringBeforeLast("\\")
                val valueName = hasRunKey.substringAfterLast("\\")
                val currentValue = editor.getDwordValue(
                    stripHivePrefix(keyPath), valueName, 0,
                ) ?: 0
                currentValue >= maxOf(1, minimumHasRunValue)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeRegistryValues(regFile: File, actions: List<Pair<String, RegistryValues>>) {
        try {
            WineRegistryEditor(regFile).use { editor ->
                editor.setCreateKeyIfNotExist(true)
                for ((key, values) in actions) {
                    for ((name, value) in values.strings) {
                        val regName = if (name == "(Default)") null else name
                        editor.setStringValue(key, regName, value)
                    }
                    for ((name, value) in values.dwords) {
                        val regName = if (name == "(Default)") null else name
                        editor.setDwordValue(key, regName, value.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to write InstallScript registry values")
        }
    }

    fun readExitCode(container: Container): Int {
        val exitFile = File(container.rootDir, ".wine/drive_c/$EXIT_CODE_FILE")
        return try {
            val code = exitFile.readText().trim().toIntOrNull() ?: -1
            exitFile.delete()
            code
        } catch (e: Exception) {
            -1
        }
    }

    private fun wrapAsGuestExecutable(cmdChain: String, screenInfo: String): String {
        val exitFile = "C:\\$EXIT_CODE_FILE"
        val wrapped = "winhandler.exe cmd /c \"($cmdChain && echo 0 > $exitFile || echo 1 > $exitFile) " +
            "& taskkill /F /IM explorer.exe & wineserver -k\""
        return "wine explorer /desktop=shell,$screenInfo $wrapped"
    }
}
