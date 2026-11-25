package app.gamenative.ui.component.dialog

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.GamepadHint
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.gcds.GcdsAdaptiveCategoryLayout
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.rememberHasExternalDisplay
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Data classes for controller configuration
 */
private data class ButtonConfig(val label: String, val keyCode: Int)
private data class AnalogConfig(val label: String, val axis: Int, val sign: Int)

/**
 * Physical Controller Configuration with two-column categorized layout
 *
 * Shows controller bindings organized by categories (Buttons, Left Stick, Right Stick, D-Pad)
 * with a UI matching the ControllerBindingDialog design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhysicalControllerConfigSection(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    resolveSaveProfile: () -> ControlsProfile?,
    onSave: (ControlsProfile) -> Unit,
    embedded: Boolean = false,
    layoutsOnly: Boolean = false,

    gameName: String = "",
) {
    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (!embedded && hasExternalDisplay && !alreadyOnSecondScreen) {
        val secondScreenContent: @Composable () -> Unit = {
            PhysicalControllerConfigSection(
                profile = profile,
                onDismiss = onDismiss,
                resolveSaveProfile = resolveSaveProfile,
                onSave = onSave,
                embedded = true,
                layoutsOnly = layoutsOnly,

                gameName = gameName,
            )
        }
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = onDismiss,
                    settingsContent = secondScreenContent,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    val context = LocalContext.current

    val defaultProfile = remember(context) { readPhysicalControllerDefaults(context) }
    val defaultBindings = remember(defaultProfile) {
        defaultProfile?.optJSONArray("controllers")
            ?.optJSONObject(0)
            ?.optJSONArray("controllerBindings")
            ?.let { bindings ->
                buildMap {
                    for (index in 0 until bindings.length()) {
                        val entry = bindings.optJSONObject(index) ?: continue
                        val keyCode = entry.optInt("keyCode", Int.MIN_VALUE)
                        val binding = entry.optString("binding")
                        if (keyCode != Int.MIN_VALUE && binding.isNotBlank()) {
                            put(keyCode, Binding.fromString(binding))
                        }
                    }
                    put(KeyEvent.KEYCODE_BUTTON_MODE, Binding.OPEN_NAVIGATION_MENU)
                }
            }.orEmpty()
    }
    val workingBindings = remember(profile, defaultBindings) {
        val controller = profile.getController("*")
        mutableStateMapOf<Int, Binding?>().apply {
            if (controller != null) {
                controller.getControllerBindings().forEach {
                    put(it.getKeyCodeForAxis(), it.getBinding())
                }
            } else {
                putAll(defaultBindings)
            }
        }
    }

    fun saveBindings(bindings: Map<Int, Binding?>) {
        val target = resolveSaveProfile() ?: return
        val controller = target.getController("*") ?: target.addController("*")
        controller.getControllerBindings().toList().forEach(controller::removeControllerBinding)
        bindings.forEach { (keyCode, binding) ->
            if (binding != null) {
                val newBinding = ExternalControllerBinding()
                newBinding.setKeyCode(keyCode)
                newBinding.setBinding(binding)
                controller.addControllerBinding(newBinding)
            }
        }
        if (!layoutsOnly) defaultProfile?.let { copyElementsIfNeeded(context, target, it) }
        target.save()
        onSave(target)
    }

    if (layoutsOnly) {
        if (embedded) BackHandler(onBack = onDismiss)
        PhysicalControllerLayoutPicker(gameName, embedded, workingBindings, defaultBindings, ::saveBindings, onDismiss)
        return
    }

    var selectedCategory by remember { mutableStateOf(0) } // 0 = Face, 1 = Shoulder, 2 = Menu, 3 = Thumbstick, 4 = Left Stick, 5 = Right Stick, 6 = D-Pad
    var showBindingDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val rowFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    val editorScrollState = rememberScrollState()
    var bindingRowFocus by remember { mutableStateOf<FocusRequester?>(null) }

    fun rowFocusRequester(keyCode: Int): FocusRequester = rowFocusRequesters.getOrPut(keyCode) {
        FocusRequester()
    }

    fun openBinding(keyCode: Int, label: String) {
        bindingRowFocus = rowFocusRequester(keyCode)
        showBindingDialog = keyCode to label
    }

    // Pre-compute all button configurations
    // Face buttons
    val faceButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_a), KeyEvent.KEYCODE_BUTTON_A),
            ButtonConfig(context.getString(R.string.button_b), KeyEvent.KEYCODE_BUTTON_B),
            ButtonConfig(context.getString(R.string.button_x), KeyEvent.KEYCODE_BUTTON_X),
            ButtonConfig(context.getString(R.string.button_y), KeyEvent.KEYCODE_BUTTON_Y)
        )
    }

    // Shoulder buttons
    val shoulderButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l1), KeyEvent.KEYCODE_BUTTON_L1),
            ButtonConfig(context.getString(R.string.button_r1), KeyEvent.KEYCODE_BUTTON_R1),
            ButtonConfig(context.getString(R.string.button_l2), KeyEvent.KEYCODE_BUTTON_L2),
            ButtonConfig(context.getString(R.string.button_r2), KeyEvent.KEYCODE_BUTTON_R2)
        )
    }

    // Menu buttons
    val menuButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_start), KeyEvent.KEYCODE_BUTTON_START),
            ButtonConfig(context.getString(R.string.button_select), KeyEvent.KEYCODE_BUTTON_SELECT),
            ButtonConfig(context.getString(R.string.button_home), KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    // Thumbstick buttons
    val thumbstickButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l3), KeyEvent.KEYCODE_BUTTON_THUMBL),
            ButtonConfig(context.getString(R.string.button_r3), KeyEvent.KEYCODE_BUTTON_THUMBR)
        )
    }

    // D-Pad
    val dpadButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.dpad_up), KeyEvent.KEYCODE_DPAD_UP),
            ButtonConfig(context.getString(R.string.dpad_down), KeyEvent.KEYCODE_DPAD_DOWN),
            ButtonConfig(context.getString(R.string.dpad_left), KeyEvent.KEYCODE_DPAD_LEFT),
            ButtonConfig(context.getString(R.string.dpad_right), KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }

    // Left analog stick
    val leftStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.left_stick_up), MotionEvent.AXIS_Y, -1),
            AnalogConfig(context.getString(R.string.left_stick_down), MotionEvent.AXIS_Y, 1),
            AnalogConfig(context.getString(R.string.left_stick_left), MotionEvent.AXIS_X, -1),
            AnalogConfig(context.getString(R.string.left_stick_right), MotionEvent.AXIS_X, 1)
        )
    }

    // Right analog stick
    val rightStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.right_stick_up), MotionEvent.AXIS_RZ, -1),
            AnalogConfig(context.getString(R.string.right_stick_down), MotionEvent.AXIS_RZ, 1),
            AnalogConfig(context.getString(R.string.right_stick_left), MotionEvent.AXIS_Z, -1),
            AnalogConfig(context.getString(R.string.right_stick_right), MotionEvent.AXIS_Z, 1)
        )
    }

    val renderInline = embedded || alreadyOnSecondScreen
    val dismissPicker = { showBindingDialog = null }
    if (renderInline) {
        BackHandler {
            if (showBindingDialog == null) onDismiss() else dismissPicker()
        }
    }

    val editorContent: @Composable () -> Unit = {
        val windowInfo = LocalWindowInfo.current
        LaunchedEffect(showBindingDialog) {
            if (showBindingDialog != null) return@LaunchedEffect
            val requester = bindingRowFocus ?: if (renderInline) rowFocusRequester(KeyEvent.KEYCODE_BUTTON_A) else return@LaunchedEffect
            if (!renderInline) {
                withTimeoutOrNull(2_000) {
                    snapshotFlow { windowInfo.isWindowFocused }.first { it }
                }
            }
            repeat(8) {
                if (runCatching { requester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                delay(32)
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.physical_controller_config),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        ConsoleIconButton(
                            onClick = onDismiss,
                            icon = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                        )
                    },
                    actions = {
                        // Reset button
                        ConsoleIconButton(
                            icon = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.reset_to_default_bindings),
                            onClick = {
                                workingBindings.clear()
                                workingBindings.putAll(defaultBindings)
                                workingBindings[KeyEvent.KEYCODE_BUTTON_MODE] = Binding.OPEN_NAVIGATION_MENU
                            },
                        )

                        ConsoleIconButton(
                            icon = Icons.Default.Save,
                            contentDescription = stringResource(R.string.save),
                            onClick = {
                                saveBindings(workingBindings)
                            },
                        )
                    }
                )
            }
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.surface
            ) {
                val categories = (0..6).toList()
                GcdsAdaptiveCategoryLayout(
                    items = categories,
                    selectedItem = selectedCategory,
                    label = { category ->
                        stringResource(
                            when (category) {
                                0 -> R.string.face_buttons_category
                                1 -> R.string.shoulder_buttons_category
                                2 -> R.string.menu_buttons_category
                                3 -> R.string.thumbstick_buttons_category
                                4 -> R.string.left_stick
                                5 -> R.string.right_stick
                                else -> R.string.dpad_category
                            },
                        )
                    },
                    onSelected = { selectedCategory = it },
                    footer = stringResource(R.string.container_config_console_controls_hint),
                    footerHints = listOf(
                        GamepadHint(listOf(GamepadButton.LB, GamepadButton.RB), R.string.hint_categories),
                        GamepadHint(GamepadButton.A, R.string.action_select),
                        GamepadHint(GamepadButton.B, R.string.back),
                    ),
                    railWidth = 220.dp,
                    requestInitialFocus = !renderInline && bindingRowFocus == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(editorScrollState)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (selectedCategory) {
                            0 -> {
                                // Face Buttons
                                faceButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        focusRequester = rowFocusRequester(buttonConfig.keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                            1 -> {
                                // Shoulder Buttons
                                shoulderButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        focusRequester = rowFocusRequester(buttonConfig.keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                            2 -> {
                                // Menu Buttons
                                menuButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        focusRequester = rowFocusRequester(buttonConfig.keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                            3 -> {
                                // Thumbstick Buttons
                                thumbstickButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        focusRequester = rowFocusRequester(buttonConfig.keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                            4 -> {
                                // Left Stick - Quick Presets
                                PhysicalControlPresets(
                                    presetType = PhysicalPresetTarget.LEFT_STICK,
                                    leftStickAxes = leftStickAxes,
                                    rightStickAxes = rightStickAxes,
                                    dpadButtons = dpadButtons,
                                    workingBindings = workingBindings,
                                )

                                // Left Stick bindings
                                leftStickAxes.forEach { analogConfig ->
                                    val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                        analogConfig.axis,
                                        analogConfig.sign.toByte()
                                    )
                                    ControllerBindingItem(
                                        label = analogConfig.label,
                                        keyCode = keyCode,
                                        focusRequester = rowFocusRequester(keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(keyCode, analogConfig.label)
                                        }
                                    )
                                }
                            }
                            5 -> {
                                // Right Stick - Quick Presets
                                PhysicalControlPresets(
                                    presetType = PhysicalPresetTarget.RIGHT_STICK,
                                    leftStickAxes = leftStickAxes,
                                    rightStickAxes = rightStickAxes,
                                    dpadButtons = dpadButtons,
                                    workingBindings = workingBindings,
                                )

                                // Right Stick bindings
                                rightStickAxes.forEach { analogConfig ->
                                    val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                        analogConfig.axis,
                                        analogConfig.sign.toByte()
                                    )
                                    ControllerBindingItem(
                                        label = analogConfig.label,
                                        keyCode = keyCode,
                                        focusRequester = rowFocusRequester(keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(keyCode, analogConfig.label)
                                        }
                                    )
                                }
                            }
                            6 -> {
                                // D-Pad - Quick Presets
                                PhysicalControlPresets(
                                    presetType = PhysicalPresetTarget.DPAD,
                                    leftStickAxes = leftStickAxes,
                                    rightStickAxes = rightStickAxes,
                                    dpadButtons = dpadButtons,
                                    workingBindings = workingBindings,
                                )

                                // D-Pad bindings
                                dpadButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        focusRequester = rowFocusRequester(buttonConfig.keyCode),
                                        workingBindings = workingBindings,
                                        onClick = {
                                            openBinding(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renderInline) {
        if (showBindingDialog == null) editorContent()
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = secondScreenDialogProperties(
                DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                ),
            )
        ) { editorContent() }
    }

    // Binding selector dialog
    showBindingDialog?.let { (keyCode, label) ->
        val currentBinding = workingBindings[keyCode]

        ControllerBindingDialog(
            buttonName = label,
            currentBinding = currentBinding,
            embedded = renderInline,
            onDismiss = dismissPicker,
            onBindingSelected = { binding ->
                if (binding != null) {
                    workingBindings[keyCode] = binding
                    Log.d("gncontrol", "Updated binding for keyCode $keyCode to $binding")
                } else {
                    workingBindings.remove(keyCode)
                    Log.d("gncontrol", "Removed binding for keyCode $keyCode")
                }

                showBindingDialog = null
            }
        )
    }
}

@Composable
private fun CategoryButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ControllerBindingItem(
    label: String,
    keyCode: Int,
    workingBindings: Map<Int, com.winlator.inputcontrols.Binding?>,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    val binding = workingBindings[keyCode]
    val bindingText = binding?.toString() ?: stringResource(R.string.not_set)
    val compact = PluviaTheme.tokens.densityCompact
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusRing(interactionSource, shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        color = if (isFocused) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = shape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 5.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = bindingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Quick preset buttons for physical controller stick/dpad bindings
 */
@Composable
private fun PhysicalControlPresets(
    presetType: PhysicalPresetTarget,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, Binding?>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_presets),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            // Keyboard/Mouse presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.WASD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_wasd), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.ARROW_KEYS,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_arrows), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.MOUSE_MOVE,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_mouse), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Gamepad presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.DPAD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_dpad), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.LEFT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_left_stick), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.RIGHT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_right_stick), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Target for physical controller presets
 */
private enum class PhysicalPresetTarget {
    LEFT_STICK, RIGHT_STICK, DPAD
}

/**
 * Binding presets for physical controller inputs
 */
private enum class PhysicalPresetBinding {
    WASD, ARROW_KEYS, MOUSE_MOVE, DPAD, LEFT_STICK, RIGHT_STICK
}

/**
 * Apply a preset binding to physical controller inputs
 */
private fun applyPhysicalPreset(
    target: PhysicalPresetTarget,
    preset: PhysicalPresetBinding,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, com.winlator.inputcontrols.Binding?>
) {
    // Define bindings for each preset (Up, Down, Left, Right order for sticks; Up, Down, Left, Right for dpad buttons)
    val bindings = when (preset) {
        PhysicalPresetBinding.WASD -> listOf(
            com.winlator.inputcontrols.Binding.KEY_W,
            com.winlator.inputcontrols.Binding.KEY_S,
            com.winlator.inputcontrols.Binding.KEY_A,
            com.winlator.inputcontrols.Binding.KEY_D
        )
        PhysicalPresetBinding.ARROW_KEYS -> listOf(
            com.winlator.inputcontrols.Binding.KEY_UP,
            com.winlator.inputcontrols.Binding.KEY_DOWN,
            com.winlator.inputcontrols.Binding.KEY_LEFT,
            com.winlator.inputcontrols.Binding.KEY_RIGHT
        )
        PhysicalPresetBinding.MOUSE_MOVE -> listOf(
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_UP,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_DOWN,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_LEFT,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_RIGHT
        )
        PhysicalPresetBinding.DPAD -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_RIGHT
        )
        PhysicalPresetBinding.LEFT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT
        )
        PhysicalPresetBinding.RIGHT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_RIGHT
        )
    }

    // Get keyCodes based on target
    val keyCodes = when (target) {
        PhysicalPresetTarget.LEFT_STICK -> {
            // Up, Down, Left, Right
            leftStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.RIGHT_STICK -> {
            // Up, Down, Left, Right
            rightStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.DPAD -> {
            // Up, Down, Left, Right
            dpadButtons.map { config ->
                config.keyCode
            }
        }
    }

    // Apply bindings
    keyCodes.forEachIndexed { index, keyCode ->
        if (keyCode != 0 && index < bindings.size) {
            workingBindings[keyCode] = bindings[index]
        }
    }
}

private fun readPhysicalControllerDefaults(context: Context): JSONObject? {
    val profilePath = "profiles/controls-0.icp"
    val installed = File(context.filesDir, profilePath)
    return runCatching {
        val json = if (installed.isFile) installed.readText() else {
            context.assets.open("inputcontrols/$profilePath").bufferedReader().use { it.readText() }
        }
        JSONObject(json)
    }.getOrNull()
}

/**
 * Copies on-screen elements from source profile to destination profile if needed.
 */
private fun copyElementsIfNeeded(context: Context, destProfile: ControlsProfile, sourceJson: JSONObject) {
    try {
        val destFile = ControlsProfile.getProfileFile(context, destProfile.id)
        if (!sourceJson.has("elements")) {
            Log.w("gncontrol", "copyElements: Source profile has no elements")
            return
        }
        val sourceElements = sourceJson.getJSONArray("elements")

        var needsCopy = false
        if (!destFile.isFile()) {
            needsCopy = true
        } else {
            val destJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            if (!destJson.has("elements") || destJson.getJSONArray("elements").length() == 0) {
                needsCopy = true
            } else {
                val destElements = destJson.getJSONArray("elements")
                var hasGamepadBindings = false
                for (i in 0 until destElements.length()) {
                    val element = destElements.getJSONObject(i)
                    if (element.has("bindings")) {
                        val bindings = element.getJSONArray("bindings")
                        for (j in 0 until bindings.length()) {
                            val binding = bindings.getString(j)
                            if (binding.startsWith("GAMEPAD_")) {
                                hasGamepadBindings = true
                                break
                            }
                        }
                    }
                    if (hasGamepadBindings) break
                }
                if (!hasGamepadBindings) {
                    needsCopy = true
                }
            }
        }

        if (needsCopy) {
            val destJson = if (destFile.isFile()) {
                org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            } else {
                org.json.JSONObject().apply {
                    put("id", destProfile.id)
                    put("name", destProfile.name)
                    put("cursorSpeed", destProfile.cursorSpeed)
                }
            }

            destJson.put("elements", sourceElements)
            com.winlator.core.FileUtils.writeString(destFile, destJson.toString())
            Log.d("gncontrol", "Copied ${sourceElements.length()} elements")
        }
    } catch (e: Exception) {
        Log.e("gncontrol", "copyElements: Failed", e)
    }
}
