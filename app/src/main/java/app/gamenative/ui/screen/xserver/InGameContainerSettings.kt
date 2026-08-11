package app.gamenative.ui.screen.xserver

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.ShooterModeConfig
import app.gamenative.data.SteamApp
import app.gamenative.data.TouchGestureConfig
import app.gamenative.ui.component.ConsoleDialogButton
import app.gamenative.ui.component.GamepadHint
import app.gamenative.ui.component.GamepadHintRow
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.secondScreenDialogProperties
import app.gamenative.utils.ContainerConfigRuntime
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.widget.FrameRating
import com.winlator.widget.XServerRendererView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * State holder for the in-game container settings overlay.
 *
 * Lives outside XServerScreen on purpose: that composable sits at the dex
 * verifier's register limit, and any extra locals there trip a VerifyError
 * at class load (same constraint as SteamInviteState). The
 * overlay is hosted by QuickMenu and owns the game pause for its whole
 * lifetime via [overlayActive], so XServerScreen needs zero new locals.
 */
class InGameContainerSettings(
    val container: Container,
) {
    var visible by mutableStateOf(false)
        private set
    var snapshot by mutableStateOf<ContainerData?>(null)
        private set
    var restartPromptVisible by mutableStateOf(false)
        private set

    companion object {
        /** True while the overlay owns the game pause (dialog or restart prompt up). */
        var overlayActive = false
    }

    fun open() {
        snapshot = runCatching { ContainerUtils.toContainerData(container) }
            .onFailure { Timber.e(it, "Failed to snapshot container config") }
            .getOrNull() ?: return
        overlayActive = true
        if (!neverSuspend()) {
            PluviaApp.xEnvironment?.onPause()
            PluviaApp.isOverlayPaused = true
        }
        visible = true
    }

    /** Dismiss without saving: close the dialog and hand the pause back to the game. */
    fun close() {
        visible = false
        snapshot = null
        overlayActive = false
        resumeAfterClose()
    }

    /**
     * Persists [newConfig] and live-applies the settings the running session
     * can adopt. Shows the restart prompt when the diff contains runtime
     * (non-live) fields, otherwise resumes the game.
     */
    fun save(context: Context, newConfig: ContainerData) {
        val old = snapshot
        val restartNeeded = old != null && ContainerConfigRuntime.requiresRestart(old, newConfig)
        applyLiveSettings(newConfig)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ContainerUtils.applyToContainer(context, container, newConfig) }
                .onFailure { Timber.e(it, "Failed to apply container config in game") }
        }
        visible = false
        snapshot = null
        if (restartNeeded) {
            restartPromptVisible = true
        } else {
            overlayActive = false
            resumeAfterClose()
        }
    }

    /** "Apply and restart": just close; the exit path relaunches via [PluviaApp.pendingRelaunchAppId]. */
    fun confirmRestart() {
        restartPromptVisible = false
        overlayActive = false
    }

    /** "Continue": close the prompt and resume. */
    fun dismissRestartPrompt() {
        restartPromptVisible = false
        overlayActive = false
        resumeAfterClose()
    }

    /** Mirrors resumeIfAllowedAfterOverlay + clearOverlayPauseState in XServerScreen. */
    private fun resumeAfterClose() {
        if (!PluviaApp.isOverlayPaused) return
        if (neverSuspend()) {
            PluviaApp.isOverlayPaused = false
            return
        }
        if (manualResume()) return
        PluviaApp.xEnvironment?.onResume()
        PluviaApp.isOverlayPaused = false
    }

    private fun neverSuspend(): Boolean =
        container.suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)

    private fun manualResume(): Boolean =
        container.suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    private fun applyLiveSettings(newConfig: ContainerData) {
        container.setDisableMouseInput(newConfig.disableMouseInput)
        container.setTouchscreenMode(newConfig.touchscreenMode)
        container.setShooterMode(newConfig.shooterMode)
        container.setGestureConfig(newConfig.gestureConfig)
        container.setShooterConfig(newConfig.shooterConfig)
        PluviaApp.touchpadView?.setTouchscreenMouseDisabled(newConfig.disableMouseInput)
        PluviaApp.touchpadView?.setTouchscreenMode(newConfig.touchscreenMode)
        if (newConfig.touchscreenMode) {
            PluviaApp.touchpadView?.setGestureConfig(TouchGestureConfig.fromJson(newConfig.gestureConfig))
        }
        PluviaApp.inputControlsView?.setContainerShooterMode(newConfig.shooterMode)
        PluviaApp.inputControlsView?.setShooterModeConfig(ShooterModeConfig.fromJson(newConfig.shooterConfig))
    }
}

/** Full exit + relaunch cycle after runtime config changes were saved. */
fun restartForConfigChange(
    container: Container,
    appId: String,
    frameRating: FrameRating?,
    currentAppInfo: SteamApp?,
    xServerView: XServerRendererView?,
    neverSuspend: Boolean,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    navigateBack: () -> Unit,
) {
    if (PluviaApp.isOverlayPaused && !neverSuspend) {
        // Resume processes before exiting so they can receive SIGTERM cleanly.
        // Don't resume audio to avoid resume->suspend race condition causing ANR.
        PluviaApp.xEnvironment?.resumeGameProcesses()
    }
    PluviaApp.isOverlayPaused = false
    if (!app.gamenative.MainActivity.wasLaunchedViaExternalIntent) {
        PluviaApp.pendingRelaunchAppId = appId
    }
    exit(
        xServerView?.getxServer()?.winHandler,
        frameRating,
        currentAppInfo,
        container,
        appId,
        onExit,
        navigateBack,
    )
}

private fun syncCursorVisibility(
    renderer: VulkanRenderer?,
    glRenderer: GLRenderer?,
    newConfig: ContainerData,
) {
    val showCursor = !newConfig.disableMouseInput &&
        (
            !newConfig.touchscreenMode ||
                TouchGestureConfig.fromJson(newConfig.gestureConfig).showCursorInTouchscreenMode
            )
    renderer?.setCursorVisible(showCursor)
    glRenderer?.setCursorVisible(showCursor)
}

@Composable
fun InGameContainerSettingsOverlay(
    state: InGameContainerSettings,
    renderer: VulkanRenderer?,
    glRenderer: GLRenderer?,
    onRestart: () -> Unit,
    embedded: Boolean = false,
) {
    if (state.visible) {
        val context = LocalContext.current
        state.snapshot?.let { snapshot ->
            ContainerConfigDialog(
                title = stringResource(R.string.quick_menu_container_settings),
                initialConfig = snapshot,
                onDismissRequest = state::close,
                onSave = { newConfig ->
                    state.save(context, newConfig)
                    syncCursorVisibility(renderer, glRenderer, newConfig)
                },
                embedded = embedded,
            )
        }
    }

    if (state.restartPromptVisible) {
        RestartRequiredDialog(
            onRestart = {
                state.confirmRestart()
                onRestart()
            },
            onContinue = state::dismissRestartPrompt,
            embedded = embedded,
        )
    }
}

@Composable
private fun RestartRequiredDialog(
    onRestart: () -> Unit,
    onContinue: () -> Unit,
    embedded: Boolean = false,
) {
    val restartFocusRequester = remember { FocusRequester() }

    val prompt: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.container_config_restart_required_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.container_config_restart_required_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConsoleDialogButton(
                        text = stringResource(R.string.container_config_save_and_continue),
                        onClick = onContinue,
                    )
                    ConsoleDialogButton(
                        text = stringResource(R.string.container_config_apply_and_restart),
                        onClick = onRestart,
                        focusRequester = restartFocusRequester,
                        isPrimary = true,
                    )
                }
                GamepadHintRow(
                    hints = listOf(
                        GamepadHint(GamepadButton.A, R.string.action_select),
                        GamepadHint(GamepadButton.B, R.string.back),
                    ),
                )
            }
        }
    }

    if (embedded) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            prompt()
        }
    } else {
        Dialog(
            onDismissRequest = onContinue,
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnClickOutside = false,
                ),
            ),
        ) {
            prompt()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { restartFocusRequester.requestFocus() }
    }
}
