package app.gamenative.ui.component.dialog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key as rememberKey
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.ConsoleDialogButton
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.ConsoleListRow
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.SettingsSearchToggle
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.rememberControllerConnectionState
import app.gamenative.ui.util.shouldShowGamepadUI
import kotlinx.coroutines.delay

internal enum class ContainerConfigPage(val titleRes: Int, val keywords: String) {
    GENERAL(
        R.string.container_config_tab_general,
        "resolution screen executable wine proton audio touchscreen store compatibility steam epic offline " +
            "разрешение экран звук запуск сенсор магазин совместимость",
    ),
    PRESETS(R.string.container_config_tab_presets, "compatibility performance preset производительность совместимость пресет"),
    GRAPHICS(R.string.container_config_tab_graphics, "graphics driver directx wrapper vulkan opengl dxvk vkd3d gamescope lsfg драйвер графика"),
    EMULATION(R.string.container_config_tab_emulation, "box64 fex cpu emulator процессор эмулятор"),
    CONTROLLER(R.string.container_config_tab_controller, "controller gamepad xinput directinput sdl vibration геймпад вибрация"),
    WINE(R.string.container_config_tab_wine, "renderer vram mouse warp csmt strict shader math видеопамять мышь рендерер"),
    WIN_COMPONENTS(R.string.container_config_tab_win_components, "directx dll vcredist component библиотека компонент"),
    ENVIRONMENT(R.string.container_config_tab_environment, "environment variable env окружение переменные"),
    DRIVES(R.string.container_config_tab_drives, "drive storage path drive letter диск путь хранилище папка"),
    MEDIA(R.string.container_config_tab_media, "artwork icon header hero media обложка фон значок логотип"),
    ADVANCED(R.string.container_config_tab_advanced, "startup services cpu affinity службы ядра процессора"),
}

private val pageGroups = listOf(
    R.string.container_pages_display_and_input to listOf(
        ContainerConfigPage.GENERAL, ContainerConfigPage.GRAPHICS, ContainerConfigPage.CONTROLLER,
    ),
    R.string.container_pages_compatibility to listOf(
        ContainerConfigPage.PRESETS, ContainerConfigPage.WINE, ContainerConfigPage.EMULATION, ContainerConfigPage.WIN_COMPONENTS,
    ),
    R.string.container_pages_files_and_launch to listOf(
        ContainerConfigPage.DRIVES, ContainerConfigPage.ENVIRONMENT, ContainerConfigPage.MEDIA, ContainerConfigPage.ADVANCED,
    ),
)

private val orderedPages = pageGroups.flatMap { it.second }

internal fun containerConfigPageAfter(selectedPage: ContainerConfigPage?, step: Int): ContainerConfigPage {
    val selectedIndex = selectedPage?.let(orderedPages::indexOf) ?: -1
    val nextIndex = if (selectedIndex < 0) {
        if (step < 0) orderedPages.lastIndex else 0
    } else {
        (selectedIndex + orderedPages.size + step) % orderedPages.size
    }
    return orderedPages[nextIndex]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContainerConfigNavigation(
    title: String,
    isDirty: Boolean,
    selectedPage: ContainerConfigPage?,
    previousPage: ContainerConfigPage?,
    onSelectPage: (ContainerConfigPage) -> Unit,
    onCyclePage: (Int) -> Unit,
    searchActive: Boolean,
    searchQuery: String,
    onOpenSearch: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    controlsBlocked: Boolean,
    embedded: Boolean,
    splitPane: Boolean,
    pageContent: @Composable (ContainerConfigPage) -> Unit,
) {
    val controller = rememberControllerConnectionState()
    val controllerFocus = LocalSecondScreenDialogWindowType.current != null || shouldShowGamepadUI() || controller.connected
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val inputMode = LocalInputModeManager.current
    var initializedSplitFocus by remember(splitPane, controller.generation) { mutableStateOf(false) }
    val compact = embedded || PluviaTheme.tokens.densityCompact
    val scrollState = rememberScrollState()
    val initialFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    BackHandler(onBack = onBack)
    LaunchedEffect(selectedPage, searchActive) { scrollState.scrollTo(0) }
    LaunchedEffect(selectedPage, searchActive, controllerFocus, controlsBlocked, splitPane, windowFocused, controller.generation) {
        if (!controllerFocus || searchActive || controlsBlocked) return@LaunchedEffect
        if (!splitPane) {
            val target = if (selectedPage == null) initialFocus else backFocus
            runCatching { target.requestFocus() }
            return@LaunchedEffect
        }
        if (!windowFocused) return@LaunchedEffect

        // Give shoulder buttons a focus target after the dialog window opens from touch.
        val bootstrap = controller.connected && !initializedSplitFocus
        if (!bootstrap && inputMode.inputMode == InputMode.Touch) return@LaunchedEffect
        repeat(4) {
            if (bootstrap && inputMode.inputMode == InputMode.Touch) {
                inputMode.requestInputMode(InputMode.Keyboard)
            }
            if (runCatching { initialFocus.requestFocus() }.getOrDefault(false)) {
                initializedSplitFocus = true
                return@LaunchedEffect
            }
            delay(32)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || searchActive || controlsBlocked) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ButtonL1, Key.ButtonL2 -> {
                        onCyclePage(-1)
                        true
                    }
                    Key.ButtonR1, Key.ButtonR2 -> {
                        onCyclePage(1)
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.ButtonB) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .then(if (embedded) Modifier else Modifier.statusBarsPadding().displayCutoutPadding())
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
                ) {
                    val showSaveLabel = maxWidth >= 480.dp && !searchActive
                    val searchWidth = (maxWidth - 112.dp).coerceIn(0.dp, 300.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val close = (splitPane || selectedPage == null) && !searchActive
                        ConsoleIconButton(
                            onClick = onBack,
                            icon = if (close) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (close) R.string.close else R.string.back),
                            focusRequester = backFocus,
                        )
                        if (!searchActive) {
                            val pageTitle = selectedPage?.let { stringResource(it.titleRes) } ?: title
                            Text(
                                text = if (isDirty) "$pageTitle *" else pageTitle,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SettingsSearchToggle(
                            active = searchActive,
                            query = searchQuery,
                            onQueryChange = onSearchQuery,
                            onOpen = onOpenSearch,
                            onClose = onCloseSearch,
                            fieldWidth = searchWidth,
                            modifier = if (searchActive) Modifier.weight(1f) else Modifier,
                        )
                        if (showSaveLabel) {
                            ConsoleDialogButton(text = stringResource(R.string.save), onClick = onSave, isPrimary = isDirty)
                        } else {
                            ConsoleIconButton(
                                onClick = onSave,
                                icon = Icons.Default.Save,
                                contentDescription = stringResource(R.string.save),
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (!searchActive && selectedPage != null) {
                GamepadActionBar(
                    actions = listOf(
                        GamepadAction(GamepadButton.LB, R.string.settings_previous_category) { onCyclePage(-1) },
                        GamepadAction(GamepadButton.RB, R.string.settings_next_category) { onCyclePage(1) },
                        GamepadAction(GamepadButton.B, R.string.back, onBack),
                    ),
                    compact = true,
                    docked = embedded,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = if (splitPane) 1200.dp else 1000.dp)
                    .fillMaxWidth()
                    .weight(1f),
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
                        orderedPages.forEach { page ->
                            val active = page == selectedPage
                            val bringIntoView = remember { BringIntoViewRequester() }
                            LaunchedEffect(active) {
                                if (active) bringIntoView.bringIntoView()
                            }
                            ContainerConfigDestination(
                                page = page,
                                onSelectPage = onSelectPage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bringIntoViewRequester(bringIntoView)
                                    .then(
                                        if (active) Modifier.background(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.shapes.medium,
                                        ) else Modifier,
                                    )
                                    .semantics { selected = active },
                                focusRequester = initialFocus.takeIf { active },
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(horizontal = if (compact) 12.dp else 24.dp, vertical = 12.dp),
                ) {
                    when {
                        searchActive && searchQuery.isNotBlank() -> {
                            val matches = orderedPages.filter { page ->
                                matchesContainerConfigTab(
                                    label = stringResource(page.titleRes),
                                    keywords = page.keywords,
                                    query = searchQuery,
                                )
                            }
                            if (matches.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.settings_search_no_results),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            } else {
                                matches.forEach { page -> ContainerConfigDestination(page, onSelectPage) }
                            }
                        }
                        selectedPage != null -> rememberKey(selectedPage) { pageContent(selectedPage) }
                        else -> ContainerConfigOverview(previousPage ?: orderedPages.first(), initialFocus, onSelectPage)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerConfigOverview(
    initialPage: ContainerConfigPage,
    initialFocus: FocusRequester,
    onSelectPage: (ContainerConfigPage) -> Unit,
) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 480.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pageGroups.forEach { (groupTitle, pages) ->
                Column {
                    Text(
                        text = stringResource(groupTitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    pages.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { page ->
                                ContainerConfigDestination(
                                    page = page,
                                    onSelectPage = onSelectPage,
                                    modifier = Modifier.weight(1f),
                                    focusRequester = initialFocus.takeIf { page == initialPage },
                                )
                            }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerConfigDestination(
    page: ContainerConfigPage,
    onSelectPage: (ContainerConfigPage) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    focusRequester: FocusRequester? = null,
) {
    ConsoleListRow(
        title = stringResource(page.titleRes),
        subtitle = null,
        onClick = { onSelectPage(page) },
        modifier = modifier,
        focusRequester = focusRequester,
    )
}
