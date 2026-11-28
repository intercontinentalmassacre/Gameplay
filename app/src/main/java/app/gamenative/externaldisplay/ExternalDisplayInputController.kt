package app.gamenative.externaldisplay

import android.app.Presentation
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import app.gamenative.PrefManager
import app.gamenative.R
import com.winlator.container.Container
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer


class ExternalDisplayInputController(
    private val context: Context,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) {
    enum class Mode { OFF, TOUCHPAD, KEYBOARD, HYBRID }

    companion object {
        fun fromConfig(value: String?): Mode = when (value?.lowercase()) {
            Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD -> Mode.TOUCHPAD
            Container.EXTERNAL_DISPLAY_MODE_KEYBOARD -> Mode.KEYBOARD
            Container.EXTERNAL_DISPLAY_MODE_HYBRID -> Mode.HYBRID
            else -> Mode.OFF
        }
    }

    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private var presentation: ExternalInputPresentation? = null
    private var mode: Mode = Mode.OFF

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            updatePresentation()
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                dismissPresentation()
            }
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (presentation?.display?.displayId == displayId) {
                updatePresentation()
            }
        }
    }

    fun start() {
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        updatePresentation()
    }

    fun stop() {
        dismissPresentation()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        updatePresentation()
    }

    private fun updatePresentation() {
        if (mode == Mode.OFF) {
            dismissPresentation()
            return
        }

        val targetDisplay = findPresentationDisplay() ?: run {
            dismissPresentation()
            return
        }

        val needsNewPresentation = presentation?.display?.displayId != targetDisplay.displayId
        if (presentation == null || needsNewPresentation) {
            dismissPresentation()
            presentation = ExternalInputPresentation(
                context = context,
                display = targetDisplay,
                mode = mode,
                xServer = xServer,
                touchpadViewProvider = touchpadViewProvider,
            )
            presentation?.show()
        } else {
            presentation?.updateMode(mode)
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun findPresentationDisplay(): Display? {
        val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        } ?: return null
        // Required detection logic for external presentation displays
        return displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { display ->
                display.displayId != currentDisplay.displayId && display.name != "HiddenDisplay"
            }
    }
}

private class ExternalInputPresentation(
    context: Context,
    display: Display,
    private var mode: ExternalDisplayInputController.Mode,
    private val xServer: XServer,
    private val touchpadViewProvider: () -> TouchpadView?,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // The second display is an interactive controller surface.  Do not mark this
        // window FLAG_NOT_FOCUSABLE: that prevents Android from dispatching D-pad,
        // A/Enter and keyboard events to its controls.
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        renderContent()
    }

    fun updateMode(newMode: ExternalDisplayInputController.Mode) {
        if (mode != newMode) {
            mode = newMode
            renderContent()
        }
    }

    private fun renderContent() {
        when (mode) {
            ExternalDisplayInputController.Mode.TOUCHPAD -> {
                val pad = TouchpadView(context, xServer, false).apply {
                    setClickHapticsEnabled(true)
                    setMotionHapticsEnabled { PrefManager.touchpadMotionHaptics }
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
                    touchpadViewProvider()?.let { primary ->
                        setSimTouchScreen(primary.isSimTouchScreen)
                    }
                }
                setContentView(pad)
            }
            ExternalDisplayInputController.Mode.KEYBOARD -> {
                val root = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
                }

                val hintIcon = ImageView(context).apply {
                    val density = resources.displayMetrics.density
                    val sizePx = (128 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(sizePx, sizePx).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(R.drawable.icon_keyboard)
                    setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val keyboardView = ExternalOnScreenKeyboardView(context, xServer).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.BOTTOM
                    }
                }

                root.addView(hintIcon)
                root.addView(keyboardView)
                setContentView(root)
                keyboardView.post { keyboardView.requestControllerFocus() }
            }
            ExternalDisplayInputController.Mode.HYBRID -> {
                val hybrid = HybridInputLayout(
                    context = context,
                    xServer = xServer,
                    touchpadViewProvider = touchpadViewProvider,
                )
                setContentView(hybrid)
                hybrid.post { hybrid.requestControllerFocus() }
            }
            else -> {
                setContentView(FrameLayout(context))
            }
        }
    }
}

internal open class CompanionTouchpadLayout(context: Context, touchpad: TouchpadView) : FrameLayout(context) {
    init {
        touchpad.setClickHapticsEnabled(true)
        touchpad.setMotionHapticsEnabled { PrefManager.touchpadMotionHaptics }
        touchpad.setBackgroundColor(ContextCompat.getColor(context, R.color.external_display_surface_background))
        touchpad.contentDescription = context.getString(R.string.live_touchpad_hint)
        addView(touchpad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(android.widget.TextView(context).apply {
            text = touchpad.contentDescription
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.external_display_key_color))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }
}

internal class HybridInputLayout private constructor(
    context: Context,
    private val touchpad: TouchpadView,
    private val keyboardView: ExternalOnScreenKeyboardView,
    keyboardInitiallyOpen: Boolean,
    private val onKeyboardToggle: ((Boolean) -> Unit)? = null,
) : CompanionTouchpadLayout(context, touchpad) {
    constructor(
        context: Context,
        xServer: XServer,
        touchpadViewProvider: () -> TouchpadView?,
        keyboardInitiallyOpen: Boolean = false,
    ) : this(
        context,
        TouchpadView(context, xServer, false).apply {
            touchpadViewProvider()?.let { setSimTouchScreen(it.isSimTouchScreen) }
        },
        ExternalOnScreenKeyboardView(context, xServer),
        keyboardInitiallyOpen,
    )

    constructor(context: Context, controls: BuranCompanionControls) : this(
        context,
        TouchpadView(context, controls).apply { setSensitivity(controls.touchSensitivity) },
        ExternalOnScreenKeyboardView(context, controls::key),
        controls.keyboardOpen,
        { controls.keyboardOpen = it },
    )

    init {
        keyboardView.apply {
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
            }
            visibility = if (keyboardInitiallyOpen) View.VISIBLE else View.GONE
        }
    }

    private val keyboardToggleButton = ImageButton(context).apply {
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()
        val marginPx = (16 * density).toInt()
        layoutParams = LayoutParams(sizePx, sizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, R.color.external_display_key_background))
        }
        setImageResource(R.drawable.icon_keyboard)
        setColorFilter(ContextCompat.getColor(context, R.color.external_display_key_color))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
        contentDescription = context.getString(R.string.keyboard)
        setOnClickListener { toggleKeyboard() }
    }

    init {
        addView(keyboardView)
        addView(keyboardToggleButton)

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateToggleButtonPosition()
        }
    }

    fun requestControllerFocus() {
        keyboardToggleButton.requestFocus()
    }

    private fun toggleKeyboard() {
        val shouldShow = keyboardView.visibility != View.VISIBLE
        onKeyboardToggle?.invoke(shouldShow)
        setKeyboardOpen(shouldShow)
        if (onKeyboardToggle != null) return
        if (shouldShow) {
            keyboardView.post { keyboardView.requestControllerFocus() }
        } else {
            keyboardToggleButton.requestFocus()
        }
    }

    fun updateCompanionControls(controls: BuranCompanionControls) {
        touchpad.isEnabled = !controls.mouseDisabled
        setKeyboardOpen(controls.keyboardOpen)
    }

    private fun setKeyboardOpen(shouldShow: Boolean) {
        if (!shouldShow && keyboardView.visibility == View.VISIBLE) keyboardView.releaseHeldKeys()
        keyboardView.visibility = if (shouldShow) View.VISIBLE else View.GONE
        post { updateToggleButtonPosition() }
    }
    private fun updateToggleButtonPosition() {
        keyboardToggleButton.translationY = if (keyboardView.visibility == View.VISIBLE) {
            -keyboardView.height.toFloat()
        } else {
            0f
        }
    }
}
