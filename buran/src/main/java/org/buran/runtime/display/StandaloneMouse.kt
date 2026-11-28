package org.buran.runtime.display

import org.buran.runtime.buranPreferences
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.buran.runtime.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Optional local controls. The guest receives ordinary pointer and button events. */
internal class StandaloneMouse(
    root: FrameLayout,
    private val host: GamescopeHostView,
    private val showToggle: Boolean = true,
    touchSensitivity: Float = 1f,
) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density
    private val fingerGain = if (showToggle) 1f else touchSensitivity / density
    private val preferences = context.buranPreferences("standalone-pointer", 0)
    private var enabled = showToggle && preferences.getBoolean("touchpad", false)
    val usesRelativeTouch get() = enabled
    private var available = false
    private var foreground = false
    private val active get() = available && foreground && host.isShown
    private var physicalPointer = false
    private var physicalInside = false
    val drawsPhysicalPointer get() = active
    private val cursorSize = (22 * density).roundToInt()
    // Drawing at the pointer position keeps damage in the full overlay's coordinate space.
    private val cursor = object : View(context) {
        private val arrow = requireNotNull(context.getDrawable(R.drawable.buran_pointer)).mutate().apply {
            setBounds(0, 0, cursorSize, cursorSize)
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val x = host.left + (xPos * (host.width - 1).coerceAtLeast(0)).roundToInt()
            val y = host.top + (yPos * (host.height - 1).coerceAtLeast(0)).roundToInt()
            val start = canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            canvas.scale(if (x + cursorSize >= host.right) -1f else 1f, if (y + cursorSize >= host.bottom) -1f else 1f)
            val hotspot = cursorSize / 24f // The vector's tip is at (1, 1) in a 24-unit viewport.
            canvas.translate(-hotspot, -hotspot)
            arrow.draw(canvas)
            canvas.restoreToCount(start)
        }
    }
    private val toggle = if (showToggle) ImageButton(context).apply {
        setImageResource(R.drawable.buran_mouse_mode)
        val inset = (13 * density).roundToInt()
        setPadding(inset, inset, inset, inset)
        setOnClickListener {
            setTouchpadEnabled(!enabled)
            preferences.edit().putBoolean("touchpad", enabled).apply()
            if (enabled) {
                Toast.makeText(context, "Палец или левый стик — курсор. Тап или A — левая кнопка, удержание или B — правая.", Toast.LENGTH_LONG).show()
            }
            host.requestFocus()
        }
    } else null
    private var xPos = .5f
    private var yPos = .5f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var finger = -1
    private var fingerX = 0f
    private var fingerY = 0f
    private var startX = 0f
    private var startY = 0f
    private var tap = false
    private var stickX = 0f
    private var stickY = 0f
    private var lastFrame = 0L
    private val held = mutableMapOf<Int, Int>()
    private val holdRight = Runnable {
        if (tap && active && enabled) {
            tap = false
            button(RIGHT, FINGER, true)
        }
    }
    private val step = object : Runnable {
        override fun run() {
            if (!active || !enabled || stickX == 0f && stickY == 0f) return
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1, 50) / 1000f
            lastFrame = now
            val distance = host.height * dt * .95f
            move(stickX * abs(stickX) * distance, stickY * abs(stickY) * distance)
            host.postOnAnimation(this)
        }
    }

    init {
        val size = (48 * density).roundToInt()
        toggle?.let { root.addView(it, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START)) }
        root.addView(cursor, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> cursor.invalidate() }
        update()
    }

    fun ready() {
        available = true
        update()
        if (active && enabled) position(xPos, yPos)
    }

    fun foreground(value: Boolean) {
        if (!value) reset()
        foreground = value
        update()
    }

    fun setTouchpadEnabled(value: Boolean) {
        if (value == enabled) return
        reset()
        physicalPointer = false
        enabled = value
        update()
        if (showToggle && enabled && active) position(xPos, yPos)
    }

    private fun update() {
        toggle?.apply {
            visibility = if (active) View.VISIBLE else View.GONE
            isSelected = enabled
            contentDescription = if (enabled) "Мышь включена. Включить прямое касание" else "Включить мышь"
            setBackgroundColor(if (enabled) 0xcc365569.toInt() else 0x77000000)
        }
        updateCursor()
    }

    private fun updateCursor() {
        val visible = active && if (physicalPointer) physicalInside else enabled
        cursor.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) cursor.invalidate()
    }

    private fun position(x: Float, y: Float) {
        xPos = x.coerceIn(0f, 1f)
        yPos = y.coerceIn(0f, 1f)
        host.sendPointer(MotionEvent.ACTION_MOVE, xPos * host.width, yPos * host.height)
        updateCursor()
    }

    fun rememberDirectPosition(x: Float, y: Float) {
        if (host.width > 0 && host.height > 0) {
            xPos = (x / host.width).coerceIn(0f, 1f)
            yPos = (y / host.height).coerceIn(0f, 1f)
        }
    }

    private fun move(dx: Float, dy: Float) {
        if (host.width > 0 && host.height > 0) position(xPos + dx / host.width, yPos + dy / host.height)
    }

    private fun button(code: Int, source: Int, down: Boolean) {
        val old = held[code] ?: 0
        val next = if (down) old or source else old and source.inv()
        if (next == 0) held.remove(code) else held[code] = next
        if ((old == 0) != (next == 0)) WaylandCompositor.nativeSendSceneInput(3, code, if (next == 0) 0 else 1)
    }

    fun reset() {
        host.removeCallbacks(holdRight)
        host.removeCallbacks(step)
        held.keys.forEach { WaylandCompositor.nativeSendSceneInput(3, it, 0) }
        held.clear()
        finger = -1
        tap = false
        stickX = 0f
        stickY = 0f
    }

    fun key(event: KeyEvent): Boolean {
        if (!showToggle || !enabled || !active) return false
        val code = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> LEFT
            KeyEvent.KEYCODE_BUTTON_B -> RIGHT
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
            button(code, PAD, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    fun motion(e: MotionEvent): Boolean {
        if (!active) return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (host.width <= 0 || host.height <= 0) return false
            physicalPointer = true
            physicalInside = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT && e.actionMasked != MotionEvent.ACTION_CANCEL
            position(e.x / host.width, e.y / host.height)
            val state = if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else e.buttonState
            button(LEFT, PHYSICAL, state and MotionEvent.BUTTON_PRIMARY != 0)
            button(RIGHT, PHYSICAL, state and MotionEvent.BUTTON_SECONDARY != 0)
            button(MIDDLE, PHYSICAL, state and MotionEvent.BUTTON_TERTIARY != 0)
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
                val v = (-120 * e.getAxisValue(MotionEvent.AXIS_VSCROLL)).roundToInt()
                val h = (120 * e.getAxisValue(MotionEvent.AXIS_HSCROLL)).roundToInt()
                WaylandCompositor.nativeSendSceneInput(11, v, h)
            }
            return true
        }
        if (!showToggle || !enabled || !e.isFromSource(InputDevice.SOURCE_JOYSTICK)) return false
        val idle = stickX == 0f && stickY == 0f
        fun axis(id: Int): Float {
            val flat = (e.device?.getMotionRange(id, e.source)?.flat ?: 0f).coerceAtLeast(.14f)
            return e.getAxisValue(id).takeIf { abs(it) > flat } ?: 0f
        }
        stickX = axis(MotionEvent.AXIS_X)
        stickY = axis(MotionEvent.AXIS_Y)
        if (stickX != 0f || stickY != 0f) {
            physicalPointer = false
            updateCursor()
        }
        if (idle && (stickX != 0f || stickY != 0f)) {
            lastFrame = SystemClock.uptimeMillis()
            host.removeCallbacks(step)
            host.postOnAnimation(step)
        }
        return true
    }

    fun touch(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return motion(e)
        if (!enabled || !active) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.requestFocus()
                physicalPointer = false
                updateCursor()
                finger = e.getPointerId(0)
                fingerX = e.x
                fingerY = e.y
                startX = e.x
                startY = e.y
                tap = true
                host.postDelayed(holdRight, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tap = false
                host.removeCallbacks(holdRight)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(finger)
                if (i >= 0) {
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (abs(x - startX) > slop || abs(y - startY) > slop) {
                        tap = false
                        host.removeCallbacks(holdRight)
                    }
                    move((x - fingerX) * fingerGain, (y - fingerY) * fingerGain)
                    fingerX = x
                    fingerY = y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex) == finger) {
                    host.removeCallbacks(holdRight)
                    if (tap && e.actionMasked == MotionEvent.ACTION_UP) {
                        button(LEFT, FINGER, true)
                        button(LEFT, FINGER, false)
                    }
                    button(RIGHT, FINGER, false)
                    tap = false
                    finger = -1
                }
            }
        }
        return true
    }

    private companion object {
        const val LEFT = 0x110
        const val RIGHT = 0x111
        const val MIDDLE = 0x112
        const val FINGER = 1
        const val PAD = 2
        const val PHYSICAL = 4
    }
}
