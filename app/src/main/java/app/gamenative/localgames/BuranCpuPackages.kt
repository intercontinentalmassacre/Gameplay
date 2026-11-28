package app.gamenative.localgames

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.winlator.container.Container
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentProfile.ContentType
import com.winlator.contents.ContentsManager
import com.winlator.core.envvars.EnvVars
import com.winlator.fexcore.FEXCorePreset
import com.winlator.fexcore.FEXCorePresetManager
import java.io.File

internal object BuranCpuPackages {
    const val EXTRA = "buranCpuPackages"
    private val fexOption = Regex("FEX_[A-Z0-9_]+")

    fun fexEnvironment(context: Context, container: Container?): ArrayList<String> {
        val preset = container?.getFEXCorePreset().orEmpty().ifBlank { FEXCorePreset.INTERMEDIATE }
        val options = FEXCorePresetManager.getEnvVars(context, preset)
        options.putAll(EnvVars(container?.envVars.orEmpty()))
        return options.filter { fexOption.matches(it) }.mapNotNullTo(ArrayList()) { name ->
            options.get(name).takeUnless { it.any(Char::isISOControl) }?.let { "$name=$it" }
        }
    }

    fun attach(context: Context, container: Container?, intent: Intent) {
        if (container == null) return
        val contents = ContentsManager(context).apply { syncContents() }
        val fex = if (container.emulator.equals("FEXCore", ignoreCase = true)) {
            fexSystem32(context, container, contents)
        } else null
        val box = sources(context, contents, ContentType.CONTENT_TYPE_BOX64, container.box64Version,
            "box64", setOf("box64"))["box64"]
        if (fex != null || box != null) intent.putExtra(EXTRA, Bundle().apply {
            fex?.let { putString("fexSystem32", it.path) }
            box?.let { putString("box64", it.path) }
        })
    }

    fun fexSystem32(context: Context, container: Container?): File? =
        container?.let { fexSystem32(context, it, ContentsManager(context).apply { syncContents() }) }

    private fun fexSystem32(context: Context, container: Container, contents: ContentsManager): File? {
        val files = sources(context, contents, ContentType.CONTENT_TYPE_FEXCORE,
            container.getFEXCoreVersion(), "fexcore", setOf("libwow64fex.dll", "libarm64ecfex.dll"))
        val wow64 = files["libwow64fex.dll"] ?: return null
        val arm64ec = files["libarm64ecfex.dll"] ?: return null
        return wow64.parentFile.takeIf {
            it == arm64ec.parentFile && wow64.name == "libwow64fex.dll" && arm64ec.name == "libarm64ecfex.dll"
        }
    }

    fun wowBox64(context: Context, container: Container?): File? {
        if (container == null || container.emulator.equals("FEXCore", ignoreCase = true)) return null
        val contents = ContentsManager(context).apply { syncContents() }
        return sources(context, contents, ContentType.CONTENT_TYPE_WOWBOX64, container.box64Version,
            "wowbox64", setOf("wowbox64.dll"))["wowbox64.dll"]
    }

    private fun sources(
        context: Context,
        contents: ContentsManager,
        type: ContentType,
        version: String,
        alternate: String,
        required: Set<String>,
    ): Map<String, File> {
        val profile = contents.getProfileByEntryName("$type-$version")
            ?.takeIf { it.type == type } ?: return emptyMap()
        val installed = ContentsManager.getInstallDir(context, profile)
        val alternateRoot = ContentsManager.getContentDir(context).resolve(alternate).resolve(profile.verName)
        return sequenceOf(installed, alternateRoot).filter { it.isDirectory }.map { root ->
            filesIn(profile, root.canonicalFile)
        }.firstOrNull { it.keys.containsAll(required) }.orEmpty()
    }

    private fun filesIn(profile: ContentProfile, root: File): Map<String, File> = buildMap {
        for (file in profile.fileList.orEmpty()) {
            val source = root.resolve(file.source).canonicalFile
            if (source.isFile && source.canRead() && source.toPath().startsWith(root.toPath())) {
                put(file.target.replace('\\', '/').substringAfterLast('/').lowercase(), source)
            }
        }
    }
}
