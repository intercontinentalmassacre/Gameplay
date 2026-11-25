package app.gamenative.ui.component.dialog

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.gamenative.PluviaApp
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import app.gamenative.R
import app.gamenative.ui.component.focusRing
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ExternalControllerBinding

internal enum class PhysicalControllerLayout(val title: Int, val description: Int) {
    GAMEPAD(R.string.gamepad, R.string.layout_gamepad_description),
    KEYBOARD_MOUSE(R.string.layout_keyboard_mouse, R.string.layout_keyboard_mouse_description),
    GAMEPAD_MOUSE(R.string.layout_gamepad_mouse, R.string.layout_gamepad_mouse_description);

    fun bindings(defaults: Map<Int, Binding>): Map<Int, Binding> = buildMap {
        putAll(defaults)
        if (this@PhysicalControllerLayout == GAMEPAD) return@buildMap

        fun axis(axis: Int, negative: Binding, positive: Binding) {
            put(ExternalControllerBinding.getKeyCodeForAxis(axis, -1), negative)
            put(ExternalControllerBinding.getKeyCodeForAxis(axis, 1), positive)
        }
        axis(MotionEvent.AXIS_Z, Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT)
        axis(MotionEvent.AXIS_RZ, Binding.MOUSE_MOVE_UP, Binding.MOUSE_MOVE_DOWN)
        if (this@PhysicalControllerLayout == GAMEPAD_MOUSE) return@buildMap

        axis(MotionEvent.AXIS_X, Binding.KEY_A, Binding.KEY_D)
        axis(MotionEvent.AXIS_Y, Binding.KEY_W, Binding.KEY_S)
        put(KeyEvent.KEYCODE_BUTTON_A, Binding.KEY_SPACE)
        put(KeyEvent.KEYCODE_BUTTON_B, Binding.KEY_CTRL_L)
        put(KeyEvent.KEYCODE_BUTTON_X, Binding.KEY_R)
        put(KeyEvent.KEYCODE_BUTTON_Y, Binding.KEY_E)
        put(KeyEvent.KEYCODE_BUTTON_R2, Binding.MOUSE_LEFT_BUTTON)
        put(KeyEvent.KEYCODE_BUTTON_L2, Binding.MOUSE_RIGHT_BUTTON)
        put(KeyEvent.KEYCODE_BUTTON_L1, Binding.MOUSE_SCROLL_UP)
        put(KeyEvent.KEYCODE_BUTTON_R1, Binding.MOUSE_SCROLL_DOWN)
        put(KeyEvent.KEYCODE_BUTTON_THUMBL, Binding.KEY_SHIFT_L)
        put(KeyEvent.KEYCODE_BUTTON_THUMBR, Binding.KEY_ENTER)
        put(KeyEvent.KEYCODE_BUTTON_START, Binding.KEY_ESC)
        put(KeyEvent.KEYCODE_BUTTON_SELECT, Binding.KEY_TAB)
        put(KeyEvent.KEYCODE_DPAD_UP, Binding.KEY_UP)
        put(KeyEvent.KEYCODE_DPAD_DOWN, Binding.KEY_DOWN)
        put(KeyEvent.KEYCODE_DPAD_LEFT, Binding.KEY_LEFT)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, Binding.KEY_RIGHT)
    }
}

@Composable
internal fun PhysicalControllerLayoutPicker(
    gameName: String,
    embedded: Boolean,
    current: Map<Int, Binding?>,
    defaults: Map<Int, Binding>,
    onApply: (Map<Int, Binding>) -> Unit,
    onDismiss: () -> Unit,
) {
    val layouts = remember(defaults) { PhysicalControllerLayout.entries.associateWith { it.bindings(defaults) } }
    val active = layouts.entries.firstOrNull { it.value == current }?.key
    var selected by remember(active) { mutableStateOf(active) }
    val initialFocus = remember { FocusRequester() }
    ConsoleSettingsPage(
        visible = true,
        title = stringResource(R.string.controller_layouts),
        onDismissRequest = onDismiss,
        embedded = embedded,
    ) {
        Column(Modifier.fillMaxSize()) {
            if (gameName.isNotBlank()) {
                Text(gameName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (active == null) {
                Text(stringResource(R.string.layout_current_custom), style = MaterialTheme.typography.bodyMedium)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).selectableGroup()) {
                for (layout in layouts.keys) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (layout == (active ?: PhysicalControllerLayout.GAMEPAD)) Modifier.focusRequester(initialFocus) else Modifier)
                            .focusRing(interaction, MaterialTheme.shapes.medium)
                            .selectable(layout == selected, role = Role.RadioButton,
                                interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current,
                                onClick = { selected = layout })
                            .padding(vertical = 9.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = layout == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(layout.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(layout.description), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { selected?.let { onApply(layouts.getValue(it)) } }, enabled = selected != null && selected != active) {
                    Text(stringResource(R.string.layout_apply))
                }
            }
        }
        LaunchedEffect(Unit) { initialFocus.requestFocus() }
    }
}


@Composable
internal fun GameControllerLayouts(appId: String, gameName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var container by remember(appId) { mutableStateOf<Container?>(null) }
    LaunchedEffect(appId) {
        try {
            container = withContext(Dispatchers.IO) {
                ContainerUtils.getOrCreateContainer(context, appId, installWinePrefix = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not load controller layouts for %s", appId)
            SnackbarManager.show(context.getString(R.string.container_settings_load_failed))
            onDismiss()
        }
    }
    val target = container
    if (target == null) {
        LoadingDialog(visible = true, onDismissRequest = onDismiss, progress = -1f, message = stringResource(R.string.working))
    } else {
        PhysicalControllerEditorRoute(target, gameName, layoutsOnly = true, onDismiss = onDismiss, onSaved = { onDismiss() })
    }
}

@Composable
internal fun PhysicalControllerEditorRoute(
    container: Container,
    gameName: String,
    layoutsOnly: Boolean,
    onDismiss: () -> Unit,
    onSaved: (ControlsProfile) -> Unit,
) {
    val context = LocalContext.current
    val manager = remember(context) { PluviaApp.inputControlsManager ?: InputControlsManager(context) }
    val profileId = container.getExtra("profileId", "0").toIntOrNull() ?: 0
    val existingProfile = remember(manager, profileId) {
        if (profileId == 0) null else manager.getProfile(profileId)
    }
    val sourceProfile = remember(manager, existingProfile) {
        existingProfile ?: manager.getProfile(0)
            ?: manager.getProfiles(false).let { profiles ->
                profiles.firstOrNull { it.id == 2 } ?: profiles.firstOrNull()
            }
    }
    if (sourceProfile == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    PhysicalControllerConfigSection(
        profile = sourceProfile,
        layoutsOnly = layoutsOnly,
        gameName = gameName,
        onDismiss = onDismiss,
        resolveSaveProfile = {
            existingProfile ?: try {
                manager.duplicateProfile(sourceProfile)?.apply {
                    setName("$gameName - Physical Controller")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create profile for container ${container.name}")
                null
            }
        },
        onSave = { profile ->
            container.putExtra("profileId", profile.id.toString())
            container.saveData()
            profile.loadControllers()
            onSaved(profile)
        },
    )
}
