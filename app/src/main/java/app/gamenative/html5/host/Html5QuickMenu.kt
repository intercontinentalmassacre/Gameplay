package app.gamenative.html5.host

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.R
import kotlin.math.roundToInt

/** Native input surface shared by every game launched in the HTML5 host. */
internal class Html5QuickMenu(
    private val context: Context,
    private val gameView: WebView,
    onOpen: () -> Unit,
    private val onResume: () -> Unit,
    onExit: () -> Unit,
) {
    private val launcher = ImageButton(context).apply {
        contentDescription = context.getString(R.string.quick_menu_title)
        setImageResource(android.R.drawable.ic_menu_more)
        setColorFilter(Color.WHITE)
        background = roundedBackground(0xcc202124.toInt())
        isFocusable = false
        setOnClickListener { onOpen() }
    }
    private val resume = action(R.string.resume_game, onResume)
    private val exit = action(R.string.system_menu_exit_game, onExit)
    private val menuOverlay = FrameLayout(context).apply {
        visibility = View.GONE
        isClickable = true
        setBackgroundColor(0x99000000.toInt())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(0xff202124.toInt())
            addView(TextView(context).apply {
                setText(R.string.quick_menu_title)
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(dp(8), 0, dp(8), dp(8))
            })
            addView(resume, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(exit, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
    }
    val view = FrameLayout(context).apply {
        addView(gameView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(launcher, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = dp(8)
        })
        addView(menuOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    val isOpen: Boolean get() = menuOverlay.visibility == View.VISIBLE
    private var lastDirection = 0

    fun show() {
        lastDirection = 0
        launcher.visibility = View.GONE
        gameView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        menuOverlay.visibility = View.VISIBLE
        resume.requestFocus()
    }

    fun hide() {
        menuOverlay.visibility = View.GONE
        launcher.visibility = View.VISIBLE
        gameView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        gameView.requestFocus()
    }

    fun handleKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> resume.requestFocusFromTouch()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> exit.requestFocusFromTouch()
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                (if (exit.hasFocus()) exit else resume).performClick()
            }
            KeyEvent.KEYCODE_BUTTON_B -> onResume()
        }
    }

    fun handleMotion(event: MotionEvent): Boolean {
        val hat = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val position = if (hat != 0f) hat else event.getAxisValue(MotionEvent.AXIS_Y)
        val direction = when {
            position < -0.5f -> -1
            position > 0.5f -> 1
            else -> 0
        }
        if (direction != lastDirection) {
            if (direction < 0) resume.requestFocusFromTouch()
            if (direction > 0) exit.requestFocusFromTouch()
            lastDirection = direction
        }
        return true
    }

    private fun action(label: Int, run: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        // Touch clicks should activate immediately. Controller navigation requests focus explicitly.
        isFocusableInTouchMode = false
        minimumWidth = dp(200)
        setOnClickListener { run() }
    }

    private fun roundedBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}
