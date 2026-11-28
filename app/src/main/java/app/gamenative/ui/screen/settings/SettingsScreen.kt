package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as rememberKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.AppTheme
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.ConsoleListRow
import app.gamenative.ui.component.SettingsSearchToggle
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.ui.util.shouldShowGamepadUI
import app.gamenative.utils.rememberHasExternalDisplay
import com.materialkolor.PaletteStyle

internal enum class SettingsCategory(
    val titleRes: Int,
    val icon: ImageVector,
) {
    INTERFACE(
        R.string.settings_interface_title,
        Icons.Default.Palette,
    ),
    CONTROLS(
        R.string.settings_controls_title,
        Icons.Default.Gamepad,
    ),
    RUNTIME(
        R.string.settings_runtime_title,
        Icons.Default.Tune,
    ),
    LIBRARY(
        R.string.settings_library_title,
        Icons.Default.LibraryBooks,
    ),
    DOWNLOADS(
        R.string.settings_downloads_title,
        Icons.Default.Download,
    ),
    SYSTEM(
        R.string.settings_system_title,
        Icons.Default.Settings,
    ),
}

internal data class SettingsSearchEntry(
    val category: SettingsCategory,
    val titleRes: Int,
    val keywords: List<String> = emptyList(),
)

internal val settingsSearchEntries = listOf(
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_interface_hide_statusbar_title),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_interface_external_links_title),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_app_theme_title, listOf("appearance", "dark", "light", "oled")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_color_profile_title, listOf("palette", "color", "contrast")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_custom_theme_edit, listOf("custom", "theme", "colors")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_interface_reduce_motion_title, listOf("accessibility", "animation", "motion")),
    SettingsSearchEntry(SettingsCategory.INTERFACE, R.string.settings_language, listOf("locale", "translation")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_interface_swap_face_buttons_title),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_gamepad_quick_menu_title, listOf("controller", "gamepad", "hotkey", "геймпад")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_interface_steam_controller_title),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_interface_warn_before_exit_title),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_achievement_play_sound),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_achievement_notification_position),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.controller_layouts, listOf("layout", "preset", "gamepad", "раскладка", "геймпад")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_achievement_show_notification, listOf("achievements", "notification")),
    SettingsSearchEntry(SettingsCategory.CONTROLS, R.string.settings_interface_show_gamepad_hints_title, listOf("controller", "gamepad", "hints")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_orientations_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_auto_apply_known_config_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_get_driver_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_contents_manager_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_shared_container_base_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_migrate_existing_prefixes_title),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_default_config_title, listOf("container defaults", "контейнер", "dxvk", "vkd3d")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_wine_proton_manager_title, listOf("wine", "proton")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_emulation_box64_presets_title, listOf("box64", "пресет")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.fexcore_presets, listOf("fex", "пресет")),
    SettingsSearchEntry(SettingsCategory.RUNTIME, R.string.settings_manage_drivers_title, listOf("gpu", "turnip", "драйвер")),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_shortcut_opens_page_title),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_second_screen_hero_title),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_second_screen_game_title),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_custom_games, listOf("local", "exe", "локальные")),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_second_screen_title, listOf("thor", "companion", "второй экран", "помощник")),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_touchpad_scroll_haptics, listOf("touchpad", "haptics", "vibration", "тачпад", "вибрация", "прокрутка")),
    SettingsSearchEntry(SettingsCategory.LIBRARY, R.string.settings_interface_second_screen_default_panel_title, listOf("thor", "companion", "стартовая панель", "второй экран")),
    SettingsSearchEntry(SettingsCategory.DOWNLOADS, R.string.save_backup_keep_title),
    SettingsSearchEntry(SettingsCategory.DOWNLOADS, R.string.settings_interface_wifi_only_title, listOf("wifi", "wi-fi", "сеть")),
    SettingsSearchEntry(SettingsCategory.DOWNLOADS, R.string.settings_interface_external_storage_title, listOf("storage", "sd", "карта памяти")),
    SettingsSearchEntry(SettingsCategory.DOWNLOADS, R.string.settings_interface_download_server_title, listOf("network", "server", "сервер загрузки")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_wine_channels_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_view_log_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_clear_cache_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_clear_db_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_clear_prefs_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_info_libraries_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_info_privacy_title),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_view_crash_title, listOf("debug", "crash", "краш", "сбой")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_save_logcat_title, listOf("logcat", "android", "журнал", "логи")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_wine_logs_title, listOf("debug", "wine", "логи", "журнал")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_debug_box_logs_title, listOf("debug", "box64", "логи", "журнал")),
    SettingsSearchEntry(SettingsCategory.SYSTEM, R.string.settings_info_source_title, listOf("about", "github", "исходники")),
)

internal fun filterSettings(
    entries: List<SettingsSearchEntry>,
    query: String,
    titleProvider: (Int) -> String,
): List<SettingsSearchEntry> {
    fun normalize(value: String) = value.lowercase().replace('ё', 'е')
    val terms = normalize(query).trim().split(Regex("\\s+"))
    if (terms.first().isEmpty()) return emptyList()
    return entries.filter { entry ->
        val text = normalize(titleProvider(entry.titleRes) + " " + entry.keywords.joinToString(" "))
        terms.all(text::contains)
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
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var lastCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val hasExternalDisplay = rememberHasExternalDisplay()
    val configuration = LocalConfiguration.current
    val splitPane = !hasExternalDisplay &&
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenWidthDp / configuration.fontScale.coerceAtLeast(1f) >= 600
    val currentCategory = selectedCategory ?: settingsCategories.first().takeIf { splitPane }

    fun closeSearch() {
        searchActive = false
        searchQuery = ""
    }

    fun openCategory(category: SettingsCategory) {
        lastCategory = category
        selectedCategory = category
        closeSearch()
    }

    fun navigateBack() {
        when {
            searchActive -> closeSearch()
            selectedCategory != null && !splitPane -> selectedCategory = null
            else -> onBack()
        }
    }

    fun cycleCategory(step: Int) {
        if (searchActive) return
        val categories = settingsCategories
        val index = currentCategory?.let(categories::indexOf) ?: -1
        val next = if (index < 0) {
            if (step > 0) categories.first() else categories.last()
        } else {
            categories[(index + step + categories.size) % categories.size]
        }
        openCategory(next)
    }

    val workspace: @Composable () -> Unit = {
        SettingsWorkspace(
            selectedCategory = currentCategory,
            lastCategory = lastCategory,
            onSelectedCategory = ::openCategory,
            searchActive = searchActive,
            searchQuery = searchQuery,
            onSearchOpen = { searchActive = true },
            onSearchQuery = { searchQuery = it },
            onSearchClose = ::closeSearch,
            onBack = ::navigateBack,
            onPreviousCategory = { cycleCategory(-1) },
            onNextCategory = { cycleCategory(1) },
            isLowerScreen = hasExternalDisplay,
            splitPane = splitPane,
        ) { category ->
            SettingsCategoryContent(
                category = category,
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

    if (hasExternalDisplay) {
        BackHandler(onBack = ::navigateBack)
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.SETTINGS,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    controllerNavigation = DsHomeSecondScreen.ControllerNavigation.VERTICAL_LIST,
                    focusRequestKey = selectedCategory,
                    onPreviousTab = { cycleCategory(-1) },
                    onNextTab = { cycleCategory(1) },
                    onBack = ::navigateBack,
                    settingsContent = workspace,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.SETTINGS) }
        }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        DualSettingsStage(
            category = selectedCategory,
            modifier = Modifier
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ButtonL1, Key.ButtonL2 -> {
                            cycleCategory(-1)
                            true
                        }
                        Key.ButtonR1, Key.ButtonR2 -> {
                            cycleCategory(1)
                            true
                        }
                        Key.ButtonB -> {
                            navigateBack()
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(focusRequester)
                .focusable(),
        )
    } else {
        workspace()
    }
}

private data class SettingsDestinationGroup(
    val titleRes: Int,
    val categories: List<SettingsCategory>,
)

private val settingsDestinations = listOf(
    SettingsDestinationGroup(
        R.string.settings_destination_play,
        listOf(SettingsCategory.CONTROLS, SettingsCategory.RUNTIME),
    ),
    SettingsDestinationGroup(
        R.string.settings_destination_collection,
        listOf(SettingsCategory.LIBRARY, SettingsCategory.DOWNLOADS),
    ),
    SettingsDestinationGroup(
        R.string.settings_destination_app,
        listOf(SettingsCategory.INTERFACE, SettingsCategory.SYSTEM),
    ),
)

private val settingsCategories = settingsDestinations.flatMap { it.categories }

@Composable
private fun DualSettingsStage(category: SettingsCategory?, modifier: Modifier = Modifier) {
    Crossfade(
        targetState = category,
        modifier = modifier.fillMaxSize(),
        animationSpec = motionSpec(tween(durationMillis = 220)),
        label = "dualSettingsStage",
    ) { current ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            val wide = maxWidth >= 480.dp
            Icon(
                imageVector = current?.icon ?: Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = if (wide) 44.dp else 12.dp)
                    .size(if (wide) 160.dp else 112.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = if (wide) 40.dp else 24.dp)
                    .fillMaxWidth(if (wide) 0.68f else 1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(current?.titleRes ?: R.string.settings_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SettingsWorkspace(
    selectedCategory: SettingsCategory?,
    lastCategory: SettingsCategory?,
    onSelectedCategory: (SettingsCategory) -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchOpen: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearchClose: () -> Unit,
    onBack: () -> Unit,
    onPreviousCategory: () -> Unit,
    onNextCategory: () -> Unit,
    isLowerScreen: Boolean,
    splitPane: Boolean,
    categoryContent: @Composable (SettingsCategory) -> Unit,
) {
    val scrollState = rememberScrollState()
    val railFocus = remember { FocusRequester() }
    val compact = isLowerScreen || PluviaTheme.tokens.densityCompact
    val backFocus = remember { FocusRequester() }
    val showGamepad = shouldShowGamepadUI()

    BackHandler(onBack = onBack)
    LaunchedEffect(selectedCategory, searchActive) { scrollState.scrollTo(0) }
    LaunchedEffect(selectedCategory, searchActive, showGamepad, splitPane) {
        if (!isLowerScreen && showGamepad && selectedCategory != null && !searchActive) {
            runCatching { (if (splitPane) railFocus else backFocus).requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (isLowerScreen) Modifier else Modifier.statusBarsPadding().displayCutoutPadding())
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || searchActive) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonL1, Key.ButtonL2 -> {
                        onPreviousCategory()
                        true
                    }
                    Key.ButtonR1, Key.ButtonR2 -> {
                        onNextCategory()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (!isLowerScreen && event.type == KeyEventType.KeyDown && event.key == Key.ButtonB) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SettingsHeader(
            title = stringResource(selectedCategory?.titleRes ?: R.string.settings_title),
            onBack = onBack,
            searchActive = searchActive,
            searchQuery = searchQuery,
            onSearchQuery = onSearchQuery,
            onSearchOpen = onSearchOpen,
            onSearchClose = onSearchClose,
            backFocus = backFocus,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = if (splitPane) 1160.dp else 960.dp)
                .fillMaxWidth(),
        ) {
            if (splitPane) {
                Column(
                    modifier = Modifier
                        .width(188.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    settingsCategories.forEach { category ->
                        val active = category == selectedCategory
                        ConsoleListRow(
                            title = stringResource(category.titleRes),
                            subtitle = null,
                            onClick = { onSelectedCategory(category) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (active) Modifier.background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.shapes.medium,
                                    ) else Modifier,
                                )
                                .semantics { selected = active },
                            focusRequester = railFocus.takeIf { active },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
                    .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 6.dp else 16.dp),
            ) {
                when {
                    searchActive && searchQuery.isNotBlank() -> SettingsSearchResults(searchQuery, onSelectedCategory)
                    selectedCategory != null -> rememberKey(selectedCategory) { categoryContent(selectedCategory) }
                    else -> SettingsOverview(lastCategory, !searchActive, onSelectedCategory)
                }
            }
        }

        if (!searchActive) {
            GamepadActionBar(
                actions = listOf(
                    GamepadAction(GamepadButton.LB, R.string.settings_previous_category, onPreviousCategory),
                    GamepadAction(GamepadButton.RB, R.string.settings_next_category, onNextCategory),
                    GamepadAction(GamepadButton.B, R.string.back, onBack),
                ),
                compact = true,
                docked = isLowerScreen,
            )
        }
    }
}

@Composable
private fun SettingsOverview(
    lastCategory: SettingsCategory?,
    restoreFocus: Boolean,
    onSelectedCategory: (SettingsCategory) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val initialCategory = lastCategory ?: settingsDestinations.first().categories.first()
    if (shouldShowGamepadUI() && restoreFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    BoxWithConstraints {
        val twoColumns = maxWidth >= 480.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            settingsDestinations.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(group.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                    val destination: @Composable (SettingsCategory, Modifier) -> Unit = { category, modifier ->
                        ConsoleListRow(
                            title = stringResource(category.titleRes),
                            subtitle = null,
                            onClick = { onSelectedCategory(category) },
                            modifier = modifier,
                            focusRequester = focusRequester.takeIf { category == initialCategory },
                        )
                    }
                    if (twoColumns) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            group.categories.forEach { category -> destination(category, Modifier.weight(1f)) }
                        }
                    } else {
                        group.categories.forEach { category -> destination(category, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSearchResults(query: String, onSelectedCategory: (SettingsCategory) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val results = remember(query, configuration) {
        filterSettings(settingsSearchEntries, query) { context.getString(it) }
    }
    if (results.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_search_no_results),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    } else {
        results.forEach { result ->
            ConsoleListRow(
                title = stringResource(result.titleRes),
                subtitle = if (result.titleRes == result.category.titleRes) null else stringResource(result.category.titleRes),
                onClick = { onSelectedCategory(result.category) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    title: String,
    onBack: () -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    backFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val compact = maxWidth < 560.dp
            val fieldWidth = if (compact) (maxWidth - 56.dp).coerceAtLeast(160.dp) else 300.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConsoleIconButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    focusRequester = backFocus,
                )
                if (!compact || !searchActive) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SettingsSearchToggle(
                    active = searchActive,
                    query = searchQuery,
                    onQueryChange = onSearchQuery,
                    onOpen = onSearchOpen,
                    onClose = onSearchClose,
                    fieldWidth = fieldWidth,
                )
            }
        }
    }
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
