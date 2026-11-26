package app.gamenative.html5.host

import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.itch.ItchGameCache
import app.gamenative.utils.CustomGameScanner
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.hardware.input.InputManager
import com.winlator.inputcontrols.ExternalController
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.html5.input.Html5GamepadBridge
import app.gamenative.html5.input.Html5InputBridge
import app.gamenative.html5.input.Html5InputSynthesizer
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.container.ContainerManager
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.GamepadState
import com.winlator.inputcontrols.InputControlsManager
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebViewClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class Html5GameActivity : Activity() {
    companion object {
        private const val ROOT = "gameRoot"
        private const val CLOSE_CART_PATH = "/.gameplay/close-cart"
        private const val PICO8_CLOSE_SCRIPT = """
            (() => {
                const close = document.getElementById('p8b_close');
                if (!close || typeof window.p8_close_cart !== 'function') return;
                close.onclick = () => window.location.assign('$CLOSE_CART_PATH');
            })();
        """

        fun entryRoot(folder: File): File? = sequenceOf(folder, File(folder, "www")).firstOrNull {
            File(it, "index.html").isFile && it.canonicalFile.toPath().startsWith(folder.canonicalFile.toPath())
        }

        fun installedRoot(appId: String): File? {
            val path = when {
                appId.startsWith("CUSTOM_GAME_") -> app.gamenative.utils.CustomGameScanner.getFolderPathFromAppId(appId)
                appId.startsWith("ITCH_") -> appId.removePrefix("ITCH_").toLongOrNull()?.let {
                    app.gamenative.itch.ItchGameCache.get(it)?.installRoot
                }
                else -> null
            }
            val folder = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
            if (appId.startsWith("ITCH_")) {
                val layout = app.gamenative.itch.ItchInstaller.layout(folder)
                app.gamenative.itch.ItchInstaller.readState(layout)?.let {
                    return entryRoot(layout.versionDir(it.activeVersionId))
                }
            }
            return entryRoot(folder)
        }

        fun launch(context: Context, appId: String, root: File) {
            context.startActivity(Intent(context, Html5GameActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(ROOT, root.path).putExtra("appId", appId))
        }
    }

    private var gameView: WebView? = null
    private var inputHandler: PhysicalControllerHandler? = null
    private var profile: ControlsProfile? = null
    private val gamepad = Html5GamepadBridge()
    private val inputEvents = Html5InputBridge()
    private val synthesizer = Html5InputSynthesizer(inputEvents)
    private val heldBindings = mutableSetOf<Binding>()
    private val inputManager by lazy { getSystemService(InputManager::class.java) }
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) {
            releaseController()
            refreshDevices()
        }
    }

    private fun refreshDevices() {
        gamepad.physicalControllerPresent = inputManager.inputDeviceIds.any {
            ExternalController.isGameController(inputManager.getInputDevice(it))
        }
    }

    private fun releaseController() {
        heldBindings.forEach { synthesizer.onBindingPress(it, false) }
        heldBindings.clear()
        profile?.gamepadState?.let { state ->
            state.copy(GamepadState())
            gamepad.updateState(state)
        }
    }

    private fun attachController(view: WebView) {
        val manager = InputControlsManager(this)
        val container = intent.getStringExtra("appId")?.let { ContainerManager(this).getContainerById(it) }
        val id = container?.getExtra("profileId", "0")?.toIntOrNull() ?: 0
        val selected = manager.getProfile(id) ?: manager.getProfile(0) ?: return
        selected.loadControllers()
        profile = selected
        inputHandler = PhysicalControllerHandler(selected, null, mappedDesktopInput = { binding, down, offset ->
            when (binding) {
                Binding.MOUSE_MOVE_LEFT -> if (down) synthesizer.onCursorMove(-kotlin.math.abs(offset), 0f)
                Binding.MOUSE_MOVE_RIGHT -> if (down) synthesizer.onCursorMove(kotlin.math.abs(offset), 0f)
                Binding.MOUSE_MOVE_UP -> if (down) synthesizer.onCursorMove(0f, -kotlin.math.abs(offset))
                Binding.MOUSE_MOVE_DOWN -> if (down) synthesizer.onCursorMove(0f, kotlin.math.abs(offset))
                else -> {
                    val changed = if (down) heldBindings.add(binding) else heldBindings.remove(binding)
                    if (changed) synthesizer.onBindingPress(binding, down)
                }
            }
        })
        view.addJavascriptInterface(gamepad, "__gnGamepadBridge")
        view.addJavascriptInterface(inputEvents, "__gnInputBridge")
        view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val density = resources.displayMetrics.density
            synthesizer.updateViewport(((r - l) / density).toInt(), ((b - t) / density).toInt())
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputHandler?.onKeyEvent(event) == true) {
            profile?.gamepadState?.let(gamepad::updateState)
            // Browsers dispatch arrow keys for a physical d-pad. Keep that path for
            // keyboard-controlled games when the profile retains the original direction.
            val sameDirection = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> Binding.GAMEPAD_DPAD_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Binding.GAMEPAD_DPAD_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> Binding.GAMEPAD_DPAD_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Binding.GAMEPAD_DPAD_DOWN
                else -> null
            }
            if (sameDirection != null && profile?.getController(event.deviceId)
                    ?.getControllerBinding(event.keyCode)?.binding == sameDirection) {
                return super.dispatchKeyEvent(event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (inputHandler?.onGenericMotionEvent(event) == true) {
            profile?.gamepadState?.let(gamepad::updateState)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = intent.getStringExtra(ROOT)?.let { File(it).canonicalFile }
        if (root == null || !File(root, "index.html").isFile) {
            finish()
            return
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(root.path.toByteArray())
        val host = digest.take(16).joinToString("") { "%02x".format(it) } + ".invalid"
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val view = WebView(this)
        gameView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            mediaPlaybackRequiresUserGesture = false
        }
        view.webViewClient = object : WebViewClient() {
            fun local(uri: Uri) = uri.scheme == "https" && uri.authority == host

            override fun onPageFinished(view: WebView, url: String) {
                if (local(Uri.parse(url))) {
                    view.evaluateJavascript(FIT_PLAY_SURFACE_JS, null)
                    view.evaluateJavascript(PICO8_CLOSE_SCRIPT, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!local(request.url)) return true
                if (request.isForMainFrame && request.url.encodedPath == CLOSE_CART_PATH) {
                    finish()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                fun missing() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
                if (!local(request.url) || request.method != "GET") return missing()
                return try {
                    val path = request.url.path.orEmpty().trimStart('/').ifEmpty { "index.html" }
                    val file = File(root, path).canonicalFile
                    if (!file.toPath().startsWith(root.toPath()) || !file.isFile) return missing()
                    val headers = mapOf("Content-Security-Policy" to "connect-src 'self' blob: data:; frame-src 'self' blob: data:; object-src 'none'")
                    WebResourceResponse(mimeFor(file.name), null, 200, "OK", headers, file.inputStream())
                } catch (_: IOException) {
                    missing()
                }
            }
        }
        // Install before the first game script creates its AudioContext. These shims
        // share the registries used by Html5MediaPause and run only at this game's origin.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            attachController(view)
            val scripts = buildList {
                add("audio-decode-serial.js")
                add("web-audio-compat.js")
                if (inputHandler != null) {
                    add("gamepad.js")
                    add("input-synth.js")
                }
                add("audio-registry.js")
                if (File(root, "js/rmmz_core.js").isFile) add("audio-latency.js")
            }
            val source = scripts.joinToString("\n") { script ->
                assets.open("html5/shims/$script").bufferedReader().use { it.readText() }
            }
            WebViewCompat.addDocumentStartJavaScript(view, source, setOf("https://$host"))
            timber.log.Timber.tag("Html5Runtime").d("Document-start shims: %s", scripts)
        }
        setContentView(view)
        view.loadUrl("https://$host/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("appId") == this.intent.getStringExtra("appId") &&
            intent.getStringExtra(ROOT) == this.intent.getStringExtra(ROOT)) return
        setIntent(intent)
        recreate()
    }

    private var dashboard: DsHomeSecondScreen.Model? = null

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val appId = intent.getStringExtra("appId").orEmpty()
        val game = appId.removePrefix("ITCH_").toLongOrNull()?.let(ItchGameCache::get)
        val title = game?.title ?: CustomGameScanner.getFolderPathFromAppId(appId)?.let { File(it).name }
            ?: File(intent.getStringExtra(ROOT).orEmpty()).name
        dashboard = DsHomeSecondScreen.Model(
            owner = DsHomeSecondScreen.Owner.GAME,
            mode = DsHomeSecondScreen.Mode.GAME_DASHBOARD,
            dashboardTitle = title,
            dashboardImageUrl = game?.coverUrl.orEmpty(),
        ).also(DsHomeSecondScreen::publish)
    }

    override fun onStop() {
        dashboard?.let(DsHomeSecondScreen::clearIfCurrent)
        dashboard = null
        super.onStop()
    }

    override fun onPause() {
        inputManager.unregisterInputDeviceListener(deviceListener)
        releaseController()
        gameView?.evaluateJavascript(PAUSE_MEDIA_JS, null)
        gameView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(deviceListener, null)
        refreshDevices()
        gameView?.onResume()
        gameView?.evaluateJavascript(RESUME_MEDIA_JS, null)
    }

    override fun onDestroy() {
        inputHandler?.cleanup()
        gameView?.let {
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        super.onDestroy()
    }
}
