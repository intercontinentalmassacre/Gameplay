package app.gamenative.utils

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.gamenative.PrefManager

/**
 * Dual-screen handheld detection (AYN Thor and similar clamshell Android
 * gaming devices). Two ways to be "dual-screen": a known dual-screen model,
 * or a second physical display currently attached.
 */
object DualScreenDevice {

    private val knownDualScreenModels = setOf(
        "thor",
        "ayn_thor",
        "ayn-thor",
    )

    fun isKnownDualScreenModel(
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
        device: String = Build.DEVICE,
    ): Boolean {
        val haystack = listOf(manufacturer, model, device)
            .joinToString(" ")
            .lowercase()
        return knownDualScreenModels.any { it in haystack }
    }

    /**
     * Returns the display that can host app UI. Presentation-category displays
     * are preferred, while the all-displays fallback keeps integrated handheld
     * panels working on firmware that does not expose that category.
     */
    fun findPresentationDisplay(context: Context): Display? {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return null
        val currentDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.displayId ?: Display.DEFAULT_DISPLAY
        } else {
            Display.DEFAULT_DISPLAY
        }
        val isUsable: (Display) -> Boolean = { display ->
            display.displayId != currentDisplayId && display.name != "HiddenDisplay"
        }
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull(isUsable)
            ?: displayManager.displays.firstOrNull(isUsable)
    }

    fun hasExternalDisplay(context: Context): Boolean = findPresentationDisplay(context) != null

    fun isDualScreen(context: Context): Boolean =
        isKnownDualScreenModel() || hasExternalDisplay(context)
}

@Composable
fun rememberIsDualScreenDevice(): Boolean {
    val context = LocalContext.current
    val externalDisplay = rememberExternalDisplay()
    val secondScreenEnabled = PrefManager.dualScreenLauncherState.value
    return remember(context, externalDisplay?.displayId, secondScreenEnabled) {
        secondScreenEnabled && (DualScreenDevice.isKnownDualScreenModel() || externalDisplay != null)
    }
}

@Composable
fun rememberExternalDisplay(): Display? {
    val context = LocalContext.current
    var display by remember(context) {
        mutableStateOf(DualScreenDevice.findPresentationDisplay(context))
    }

    DisposableEffect(context) {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (displayManager == null) {
            display = null
            return@DisposableEffect onDispose { }
        }
        val listener = object : DisplayManager.DisplayListener {
            private fun refresh() {
                display = DualScreenDevice.findPresentationDisplay(context)
            }

            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        display = DualScreenDevice.findPresentationDisplay(context)
        onDispose {
            runCatching { displayManager.unregisterDisplayListener(listener) }
        }
    }
    return display
}

@Composable
fun rememberHasExternalDisplay(): Boolean {
    val secondScreenEnabled = PrefManager.dualScreenLauncherState.value
    return secondScreenEnabled && rememberExternalDisplay() != null
}
