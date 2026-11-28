package org.buran.runtime.hud

import android.content.Context
import android.widget.FrameLayout

/** Session lifecycle for Gameplay's performance widget on the Gamescope surface. */
internal class GamescopePerformanceHud(
    context: Context,
    fpsProvider: () -> Float?,
    generatedFpsProvider: () -> Int?,
) : FrameLayout(context) {
    private val widget = PerformanceHudView(context, fpsProvider, generatedFpsProvider = generatedFpsProvider)

    init {
        visibility = GONE
        isClickable = false
        isFocusable = false
        widget.pause()
        addView(widget, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setConfig(config: PerformanceHudConfig, compactMode: Boolean) {
        widget.setConfig(config)
        widget.setCompactMode(compactMode)
    }

    fun setSampling(enabled: Boolean) {
        if (enabled == (visibility == VISIBLE)) return
        visibility = if (enabled) VISIBLE else GONE
        if (enabled) widget.resume() else widget.pause()
    }

    override fun onDetachedFromWindow() {
        setSampling(false)
        super.onDetachedFromWindow()
    }
}
