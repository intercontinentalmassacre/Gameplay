package app.gamenative.localgames

import com.winlator.core.WineRegistryEditor
import java.io.File

/** Let Wine create one desktop for installers, launchers and the game in this prefix. */
internal fun configureBuranWineDesktop(prefix: File, enabled: Boolean) {
    val userRegistry = prefix.resolve("user.reg")
    if (!userRegistry.isFile) return
    WineRegistryEditor(userRegistry).use { registry ->
        val explorer = "Software\\Wine\\Explorer"
        val desktops = "$explorer\\Desktops"
        val name = "Buran"
        if (enabled) {
            registry.setCreateKeyIfNotExist(true)
            registry.setStringValue(desktops, name, "1280x720")
            registry.setStringValue(explorer, "Desktop", name)
        } else if (registry.getStringValue(explorer, "Desktop") == name) {
            registry.removeValue(explorer, "Desktop")
            registry.removeValue(desktops, name)
        }
    }
}
