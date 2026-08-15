package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.AppTheme
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.ConsoleCategoryRail
import app.gamenative.ui.gcds.GcdsRail
import app.gamenative.ui.gcds.GcdsStrip
import app.gamenative.ui.gcds.GcdsAdaptiveCategoryLayout
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadHint
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.ConsoleListRow
import app.gamenative.ui.component.DualScreenAmbientStage
import app.gamenative.ui.component.SettingsSearchToggle
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.utils.rememberHasExternalDisplay
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SettingsCategory(
    val titleRes: Int,
    val stageDescriptionRes: Int,
    val icon: ImageVector,
) {
    INTERFACE(
        R.string.settings_interface_title,
        R.string.settings_stage_interface_description,
        Icons.Default.Palette,
    ),
    CONTROLS(
        R.string.settings_controls_title,
        R.string.settings_stage_controls_description,
        Icons.Default.Gamepad,
    ),
    RUNTIME(
        R.string.settings_runtime_title,
        R.string.settings_stage_runtime_description,
        Icons.Default.Tune,
    ),
    LIBRARY(
        R.string.settings_library_title,
        R.string.settings_stage_library_description,
        Icons.Default.LibraryBooks,
    ),
    DOWNLOADS(
        R.string.settings_downloads_title,
        R.string.settings_stage_downloads_description,
        Icons.Default.Download,
    ),
    SYSTEM(
        R.string.settings_system_title,
        R.string.settings_stage_system_description,
        Icons.Default.Settings,
    ),
}

internal data class SettingsSearchEntry(
    val category: SettingsCategory,
    val titleRes: Int,
    val keywords: List<String> = emptyList(),
)

internal val settingsSearchEntries = listOf(
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_app_theme_title, listOf("appearance", "dark", "light", "oled")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_color_profile_title, listOf("palette", "color", "contrast")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_custom_theme_edit, listOf("custom", "theme", "colors")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_interface_reduce_motion_title, listOf("accessibility", "animation", "motion")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_language, listOf("locale", "translation")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_achievement_show_notification, listOf("achievements", "notification")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_interface_show_gamepad_hints_title, listOf("controller", "gamepad", "hints")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_runtime_title, listOf("wine", "proton", "box64", "fex", "dxvk", "vkd3d")),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_custom_games, listOf("local", "exe", "games")),
    SettingsSearchEntry(SettingsCategory.DOWNLOADS, R.string.settings_downloads_title, listOf("storage", "network", "wifi", "server")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_system_title, listOf("debug", "about", "logs")),
)

internal fun filterSettings(
    entries: List<SettingsSearchEntry>,
    query: String,
    titleProvider: (Int) -> String,
): List<SettingsSearchEntry> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return emptyList()
    return entries.filter { entry ->
        titleProvider(entry.titleRes).lowercase().contains(normalizedQuery) ||
            entry.keywords.any { keyword -> keyword.contains(normalizedQuery) }
    }
}

@Composable
fun SettingsScreen(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    customThemeEnabled: Boolean,
    customThemeJson: String,
    onCustomTheme: (String) -> Unit,
    onCustomThemeEnabled: (Boolean) -> Unit,
    onClearCustomTheme: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScreenContent(
        appTheme = appTheme,
        paletteStyle = paletteStyle,
        onAppTheme = onAppTheme,
        onPaletteStyle = onPaletteStyle,
        customThemeEnabled = customThemeEnabled,
        customThemeJson = customThemeJson,
        onCustomTheme = onCustomTheme,
        onCustomThemeEnabled = onCustomThemeEnabled,
        onClearCustomTheme = onClearCustomTheme,
        onBack = onBack,
    )
}

@Composable
private fun SettingsScreenContent(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    customThemeEnabled: Boolean,
    customThemeJson: String,
    onCustomTheme: (String) -> Unit,
    onCustomThemeEnabled: (Boolean) -> Unit,
    onClearCustomTheme: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val categories = remember { SettingsCategory.entries.toList() }
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.INTERFACE) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val categoryRailWidth = if (LocalConfiguration.current.screenWidthDp < 600) 156.dp else 228.dp
    val hasExternalDisplay = rememberHasExternalDisplay()

    val closeSearch: () -> Unit = {
        searchActive = false
        searchQuery = ""
    }

    BackHandler(enabled = searchActive, onBack = closeSearch)

    LaunchedEffect(selectedCategory) {
        scrollState.scrollTo(0)
    }

    if (hasExternalDisplay) {
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.SETTINGS,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    controllerNavigation = DsHomeSecondScreen.ControllerNavigation.VERTICAL_LIST,
                    focusRequestKey = selectedCategory,
                    onPreviousTab = {
                        val index = categories.indexOf(selectedCategory).coerceAtLeast(0)
                        selectedCategory = categories[(index - 1 + categories.size) % categories.size]
                    },
                    onNextTab = {
                        val index = categories.indexOf(selectedCategory).coerceAtLeast(0)
                        selectedCategory = categories[(index + 1) % categories.size]
                    },
                    onBack = onBack,
                    settingsContent = {
                        DualSettingsWorkspace(
                            appTheme = appTheme,
                            paletteStyle = paletteStyle,
                            onAppTheme = onAppTheme,
                            onPaletteStyle = onPaletteStyle,
                            customThemeEnabled = customThemeEnabled,
                            customThemeJson = customThemeJson,
                            onCustomTheme = onCustomTheme,
                            onCustomThemeEnabled = onCustomThemeEnabled,
                            onClearCustomTheme = onClearCustomTheme,
                            selectedCategory = selectedCategory,
                            onSelectedCategory = { selectedCategory = it },
                            searchActive = searchActive,
                            searchQuery = searchQuery,
                            onSearchActive = { searchActive = it },
                            onSearchQuery = { searchQuery = it },
                            onBack = onBack,
                        )
                    },
                ),
            )
        }
        DisposableEffect(hasExternalDisplay) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.SETTINGS) }
        }
        DualSettingsStage(
            selectedCategory = selectedCategory,
            onSelectedCategory = { selectedCategory = it },
            onBack = onBack,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
                SettingsHeader(
                    onBack = onBack,
                    searchActive = searchActive,
                    searchQuery = searchQuery,
                    onSearchQuery = { searchQuery = it },
                    onSearchOpen = { searchActive = true },
                    onSearchClose = closeSearch,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

            GcdsAdaptiveCategoryLayout(
                items = categories,
                selectedItem = selectedCategory,
                label = { stringResource(it.titleRes) },
                onSelected = { selectedCategory = it },
                footer = stringResource(R.string.container_config_console_controls_hint),
                footerHints = listOf(
                    GamepadHint(listOf(GamepadButton.LB, GamepadButton.RB), R.string.hint_categories),
                    GamepadHint(GamepadButton.A, R.string.action_select),
                    GamepadHint(GamepadButton.B, R.string.back),
                ),
                railWidth = categoryRailWidth,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(start = 22.dp, end = 22.dp, bottom = 32.dp),
                ) {
                    Spacer(modifier = Modifier.height(10.dp))

                    if (searchActive && searchQuery.isNotBlank()) {
                        val results = filterSettings(
                            entries = settingsSearchEntries,
                            query = searchQuery,
                            titleProvider = { resourceId -> context.getString(resourceId) },
                        )
                        if (results.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_search_no_results),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            results.forEach { result ->
                                ConsoleListRow(
                                    title = stringResource(result.titleRes),
                                    subtitle = stringResource(result.category.titleRes),
                                    onClick = {
                                        selectedCategory = result.category
                                        closeSearch()
                                    },
                                )
                            }
                        }
                    } else when (selectedCategory) {
                        SettingsCategory.INTERFACE -> SettingsGroupInterface(
                            appTheme = appTheme,
                            paletteStyle = paletteStyle,
                            onAppTheme = onAppTheme,
                            onPaletteStyle = onPaletteStyle,
                            customThemeEnabled = customThemeEnabled,
                            customThemeJson = customThemeJson,
                            onCustomTheme = onCustomTheme,
                            onCustomThemeEnabled = onCustomThemeEnabled,
                            onClearCustomTheme = onClearCustomTheme,
                            section = InterfaceSettingsSection.APPEARANCE,
                        )
                        SettingsCategory.CONTROLS -> SettingsGroupInterface(
                            appTheme = appTheme,
                            paletteStyle = paletteStyle,
                            onAppTheme = onAppTheme,
                            onPaletteStyle = onPaletteStyle,
                            section = InterfaceSettingsSection.CONTROLS,
                        )
                        SettingsCategory.RUNTIME -> SettingsGroupEmulation()
                        SettingsCategory.LIBRARY -> SettingsGroupInterface(
                            appTheme = appTheme,
                            paletteStyle = paletteStyle,
                            onAppTheme = onAppTheme,
                            onPaletteStyle = onPaletteStyle,
                            section = InterfaceSettingsSection.LIBRARY,
                        )
                        SettingsCategory.DOWNLOADS -> SettingsGroupInterface(
                            appTheme = appTheme,
                            paletteStyle = paletteStyle,
                            onAppTheme = onAppTheme,
                            onPaletteStyle = onPaletteStyle,
                            section = InterfaceSettingsSection.DOWNLOADS,
                        )
                        SettingsCategory.SYSTEM -> {
                            SettingsGroupInfo()
                            Spacer(modifier = Modifier.height(20.dp))
                            SettingsGroupDebug()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DualSettingsStage(
    selectedCategory: SettingsCategory,
    onSelectedCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
) {
    val categories = remember { SettingsCategory.entries.toList() }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val index = categories.indexOf(selectedCategory).coerceAtLeast(0)
                when (event.key) {
                    Key.ButtonR1, Key.ButtonR2 -> {
                        onSelectedCategory(categories[(index + 1) % categories.size])
                        true
                    }
                    Key.ButtonL1, Key.ButtonL2 -> {
                        onSelectedCategory(categories[(index - 1 + categories.size) % categories.size])
                        true
                    }
                    Key.ButtonB -> {
                        onBack()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Crossfade(
            targetState = selectedCategory,
            animationSpec = motionSpec(tween(durationMillis = 220)),
            label = "dualSettingsStage",
        ) { category ->
            DualSettingsCategoryScene(category = category)
        }
    }
}

@Composable
private fun DualSettingsCategoryScene(category: SettingsCategory) {
    val accent = when (category) {
        SettingsCategory.INTERFACE -> MaterialTheme.colorScheme.primary
        SettingsCategory.CONTROLS -> PluviaTheme.colors.accentPurple
        SettingsCategory.RUNTIME -> PluviaTheme.colors.accentCyan
        SettingsCategory.LIBRARY -> PluviaTheme.colors.accentSuccess
        SettingsCategory.DOWNLOADS -> PluviaTheme.colors.statusDownloading
        SettingsCategory.SYSTEM -> PluviaTheme.colors.accentWarning
    }

    DualScreenAmbientStage(
        icon = category.icon,
        label = stringResource(R.string.settings_title),
        title = stringResource(category.titleRes),
        description = stringResource(category.stageDescriptionRes),
        accent = accent,
        hint = stringResource(R.string.settings_stage_navigation_hint),
    )
}

@Composable
private fun DualSettingsWorkspace(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    customThemeEnabled: Boolean,
    customThemeJson: String,
    onCustomTheme: (String) -> Unit,
    onCustomThemeEnabled: (Boolean) -> Unit,
    onClearCustomTheme: () -> Unit,
    selectedCategory: SettingsCategory,
    onSelectedCategory: (SettingsCategory) -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchActive: (Boolean) -> Unit,
    onSearchQuery: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val categories = remember { SettingsCategory.entries.toList() }
    val scrollState = rememberScrollState()
    val controllerFocusEpoch = DsHomeSecondScreen.controllerFocusEpoch
    val closeSearch = {
        onSearchActive(false)
        onSearchQuery("")
    }

    BackHandler(enabled = searchActive, onBack = closeSearch)
    BackHandler(enabled = !searchActive, onBack = onBack)

    LaunchedEffect(selectedCategory) { scrollState.scrollTo(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val index = categories.indexOf(selectedCategory).coerceAtLeast(0)
                when (event.key) {
                    Key.ButtonR1, Key.ButtonR2 -> {
                        onSelectedCategory(categories[(index + 1) % categories.size])
                        true
                    }
                    Key.ButtonL1, Key.ButtonL2 -> {
                        onSelectedCategory(categories[(index - 1 + categories.size) % categories.size])
                        true
                    }
                    Key.ButtonB -> {
                        if (searchActive) closeSearch() else onBack()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
        ) {
            SettingsHeader(
                onBack = onBack,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchQuery = onSearchQuery,
                onSearchOpen = { onSearchActive(true) },
                onSearchClose = closeSearch,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )

            GcdsStrip(
                items = categories,
                selectedItem = selectedCategory,
                label = { stringResource(it.titleRes) },
                onSelected = onSelectedCategory,
                controllerFocusable = false,
                requestInitialFocus = false,
                focusRequestKey = controllerFocusEpoch,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (searchActive && searchQuery.isNotBlank()) {
                    val results = filterSettings(
                        entries = settingsSearchEntries,
                        query = searchQuery,
                        titleProvider = { context.getString(it) },
                    )
                    if (results.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_search_no_results),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        results.forEach { result ->
                            ConsoleListRow(
                                title = stringResource(result.titleRes),
                                subtitle = stringResource(result.category.titleRes),
                                onClick = {
                                    onSelectedCategory(result.category)
                                    closeSearch()
                                },
                            )
                        }
                    }
                } else {
                    SettingsCategoryContent(
                        category = selectedCategory,
                        appTheme = appTheme,
                        paletteStyle = paletteStyle,
                        onAppTheme = onAppTheme,
                        onPaletteStyle = onPaletteStyle,
                        customThemeEnabled = customThemeEnabled,
                        customThemeJson = customThemeJson,
                        onCustomTheme = onCustomTheme,
                        onCustomThemeEnabled = onCustomThemeEnabled,
                        onClearCustomTheme = onClearCustomTheme,
                    )
                }
            }
        }

        GamepadActionBar(
            actions = listOf(
                GamepadAction(GamepadButton.LB, R.string.hint_categories),
                GamepadAction(GamepadButton.RB, R.string.hint_categories),
                GamepadAction(GamepadButton.B, R.string.back, onBack),
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
            forceVisible = true,
            compact = true,
        )
    }
}

@Composable
private fun SettingsCategoryContent(
    category: SettingsCategory,
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    customThemeEnabled: Boolean,
    customThemeJson: String,
    onCustomTheme: (String) -> Unit,
    onCustomThemeEnabled: (Boolean) -> Unit,
    onClearCustomTheme: () -> Unit,
) {
    when (category) {
        SettingsCategory.INTERFACE -> SettingsGroupInterface(
            appTheme = appTheme,
            paletteStyle = paletteStyle,
            onAppTheme = onAppTheme,
            onPaletteStyle = onPaletteStyle,
            customThemeEnabled = customThemeEnabled,
            customThemeJson = customThemeJson,
            onCustomTheme = onCustomTheme,
            onCustomThemeEnabled = onCustomThemeEnabled,
            onClearCustomTheme = onClearCustomTheme,
            section = InterfaceSettingsSection.APPEARANCE,
        )
        SettingsCategory.CONTROLS -> SettingsGroupInterface(
            appTheme = appTheme,
            paletteStyle = paletteStyle,
            onAppTheme = onAppTheme,
            onPaletteStyle = onPaletteStyle,
            section = InterfaceSettingsSection.CONTROLS,
        )
        SettingsCategory.RUNTIME -> SettingsGroupEmulation()
        SettingsCategory.LIBRARY -> SettingsGroupInterface(
            appTheme = appTheme,
            paletteStyle = paletteStyle,
            onAppTheme = onAppTheme,
            onPaletteStyle = onPaletteStyle,
            section = InterfaceSettingsSection.LIBRARY,
        )
        SettingsCategory.DOWNLOADS -> SettingsGroupInterface(
            appTheme = appTheme,
            paletteStyle = paletteStyle,
            onAppTheme = onAppTheme,
            onPaletteStyle = onPaletteStyle,
            section = InterfaceSettingsSection.DOWNLOADS,
        )
        SettingsCategory.SYSTEM -> {
            SettingsGroupInfo()
            Spacer(modifier = Modifier.height(20.dp))
            SettingsGroupDebug()
        }
    }
}

@Composable
private fun SettingsHeader(
    onBack: () -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < 520.dp
        val availableWidth = maxWidth
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
        ) {
            BackButton(onClick = onBack)

            if (!compact || !searchActive) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (!compact) {
                        Text(
                            text = stringResource(R.string.settings_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = PluviaTheme.colors.textMuted,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            SettingsSearchToggle(
                active = searchActive,
                query = searchQuery,
                onQueryChange = onSearchQuery,
                onOpen = onSearchOpen,
                onClose = onSearchClose,
                fieldWidth = if (compact) (availableWidth - 72.dp).coerceAtLeast(160.dp) else 300.dp,
            )
        }
    }
}

@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConsoleIconButton(
        onClick = onClick,
        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.back),
        modifier = modifier,
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_SettingsScreen() {
    val isPreview = LocalInspectionMode.current
    if (!isPreview) {
        val context = LocalContext.current
        PrefManager.init(context)
    }
    PluviaTheme {
        SettingsScreenContent(
            appTheme = AppTheme.DAY,
            paletteStyle = PaletteStyle.TonalSpot,
            onAppTheme = { },
            onPaletteStyle = { },
            customThemeEnabled = false,
            customThemeJson = "",
            onCustomTheme = { },
            onCustomThemeEnabled = { },
            onClearCustomTheme = { },
            onBack = { },
        )
    }
}
