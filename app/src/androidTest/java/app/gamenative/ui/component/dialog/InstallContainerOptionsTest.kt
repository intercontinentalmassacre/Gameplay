package app.gamenative.ui.component.dialog

import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.container.ContainerManager
import com.winlator.core.KeyValueSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallContainerOptionsTest {
    @Test fun installerCommitsTheDraftBeforeStartingTheDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ContainerManager(context)
        val id = "STEAM_2147483601"
        check(manager.getContainerById(id) == null)
        val data = JSONObject().put("containerVariant", "bionic").put("wineVersion", "proton-11.0-1-arm64ec-1")
        val container = checkNotNull(manager.createContainer(id, data, false))
        try {
            val original = container.configFile.readText()
            val options = InstallContainerOptions(context, id)
            options.load()
            options.ready = true
            options.shared = true
            options.preset = compatPresets.single { it.id == "OLD_GAMES" }
            options.dxvk = "3.1.1-0"
            assertEquals(original, container.configFile.readText())
            val result = CompletableDeferred<String>()
            options.install(this) { result.complete(container.configFile.readText()) }
            val saved = JSONObject(withTimeout(15000) { result.await() })
            assertEquals("1280x960", saved.getString("screenSize"))
            assertEquals("dxvk", saved.getString("dxwrapper"))
            assertEquals("3.1.1-0", KeyValueSet(saved.getString("dxwrapperConfig")).get("version"))
            assertEquals("shared", saved.getJSONObject("extraData").getString("downloadPrefixStorage"))
            assertEquals("1", saved.getJSONObject("extraData").getString("overlayRequested"))
        } finally {
            assertTrue(container.rootDir.deleteRecursively())
        }
    }
}
