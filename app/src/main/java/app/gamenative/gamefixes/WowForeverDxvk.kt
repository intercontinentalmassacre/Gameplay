package app.gamenative.gamefixes

import com.winlator.container.Container
import com.winlator.core.KeyValueSet

private const val WOW_FOREVER_DXVK = "2.4.1-wowfix-1"

// The native Windows ARM64 client needs ARM64 DLLs. Select them before component installation.
internal fun configureWowForeverDxvk(executable: String, container: Container) {
    val filename = executable.substringAfterLast('\\').substringAfterLast('/')
    if (!filename.equals("WowB-ARM64.exe", ignoreCase = true) || container.containerVariant != Container.BIONIC) return

    val config = KeyValueSet(container.getDXWrapperConfig())
    if (container.getDXWrapper() == "dxvk" && config.get("version") == WOW_FOREVER_DXVK) return

    config.put("version", WOW_FOREVER_DXVK)
    container.setDXWrapper("dxvk")
    container.setDXWrapperConfig(config.toString())
    container.saveData()
}
