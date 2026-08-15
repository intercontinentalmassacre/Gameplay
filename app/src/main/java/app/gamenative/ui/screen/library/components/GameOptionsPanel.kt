package app.gamenative.ui.screen.library.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AddToHomeScreen
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.gcds.GcdsRail
import app.gamenative.ui.gcds.GcdsStrip
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec

@Composable
fun GameOptionsPanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    options: List<AppMenuOption>,
    gameName: String? = null,
    compactDisplay: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val firstItemFocusRequester = remember { FocusRequester() }
    val groupedOptions = remember(options) { groupOptions(options) }
    val availableCategories = remember(groupedOptions) {
        groupedOptions.filterValues { it.isNotEmpty() }.keys.toList()
    }
    var selectedCategory by remember(availableCategories) {
        mutableStateOf(availableCategories.firstOrNull() ?: OptionCategory.QUICK_ACTIONS)
    }

    LaunchedEffect(isOpen, selectedCategory) {
        if (isOpen && groupedOptions[selectedCategory].orEmpty().isNotEmpty()) {
            kotlinx.coroutines.delay(80)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus request may fail if composition is not ready
            }
        }
    }

    BackHandler(enabled = isOpen, onBack = onDismiss)

    AnimatedVisibility(
        visible = isOpen,
        enter = fadeIn(motionSpec(tween(140))),
        exit = fadeOut(motionSpec(tween(110))),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f))
                .selectable(
                    selected = false,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = isOpen,
        enter = slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = motionSpec(tween(180)),
        ) + fadeIn(motionSpec(tween(140))),
        exit = slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = motionSpec(tween(140)),
        ) + fadeOut(motionSpec(tween(110))),
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (compactDisplay) Modifier.fillMaxWidth()
                else Modifier.widthIn(max = 1120.dp).fillMaxWidth(0.92f),
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            // compactDisplay means "occupy the companion workspace", not
            // "pretend that workspace is narrow". The actual content width
            // decides whether categories use a strip or a rail.
            val useCompactLayout = compactDisplay || maxWidth < 600.dp
            val categoryLabels = mapOf(
                OptionCategory.QUICK_ACTIONS to stringResource(R.string.game_options_quick_actions),
                OptionCategory.GAME_MANAGEMENT to stringResource(R.string.game_options_game_management),
                OptionCategory.CONTAINER to stringResource(R.string.game_options_container),
                OptionCategory.CLOUD_SAVES to stringResource(R.string.game_options_cloud_saves),
                OptionCategory.HELP_INFO to stringResource(R.string.game_options_help_info),
            )
            val categoryDescriptions = mapOf(
                OptionCategory.QUICK_ACTIONS to stringResource(R.string.game_options_quick_actions_description),
                OptionCategory.GAME_MANAGEMENT to stringResource(R.string.game_options_game_management_description),
                OptionCategory.CONTAINER to stringResource(R.string.game_options_container_description),
                OptionCategory.CLOUD_SAVES to stringResource(R.string.game_options_cloud_saves_description),
                OptionCategory.HELP_INFO to stringResource(R.string.game_options_help_info_description),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown || availableCategories.isEmpty()) {
                            return@onPreviewKeyEvent false
                        }
                        val currentIndex = availableCategories.indexOf(selectedCategory).coerceAtLeast(0)
                        when (event.key) {
                            Key.ButtonR1, Key.ButtonR2 -> {
                                selectedCategory = availableCategories[(currentIndex + 1) % availableCategories.size]
                                true
                            }
                            Key.ButtonL1, Key.ButtonL2 -> {
                                selectedCategory = availableCategories[
                                    (currentIndex - 1 + availableCategories.size) % availableCategories.size
                                ]
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                ConsolePanelHeader(
                    title = gameName?.let {
                        stringResource(R.string.game_options_title_for_game, it)
                    } ?: stringResource(R.string.game_options_title),
                    onBack = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (useCompactLayout) {
                    GcdsStrip(
                        items = availableCategories,
                        selectedItem = selectedCategory,
                        label = { categoryLabels[it] ?: it.name },
                        onSelected = { selectedCategory = it },
                        requestInitialFocus = false,
                        controllerFocusable = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CategoryContent(
                        title = categoryLabels[selectedCategory] ?: selectedCategory.name,
                        description = categoryDescriptions[selectedCategory].orEmpty(),
                        options = groupedOptions[selectedCategory].orEmpty(),
                        onOptionClick = { option ->
                            option.onClick()
                            onDismiss()
                        },
                        firstItemFocusRequester = firstItemFocusRequester,
                        compact = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    Row(modifier = Modifier.weight(1f)) {
                        GcdsRail(
                            items = availableCategories,
                            selectedItem = selectedCategory,
                            label = { categoryLabels[it] ?: it.name },
                            onSelected = { selectedCategory = it },
                            footer = "L1 / R1",
                            requestInitialFocus = false,
                            controllerFocusable = false,
                            compact = true,
                            modifier = Modifier
                                .width(210.dp)
                                .fillMaxHeight(),
                        )
                        CategoryContent(
                            title = categoryLabels[selectedCategory] ?: selectedCategory.name,
                            description = categoryDescriptions[selectedCategory].orEmpty(),
                            options = groupedOptions[selectedCategory].orEmpty(),
                            onOptionClick = { option ->
                                option.onClick()
                                onDismiss()
                            },
                            firstItemFocusRequester = firstItemFocusRequester,
                            compact = false,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 12.dp),
                        )
                    }
                }

                GamepadActionBar(
                    actions = listOf(
                        GamepadAction(GamepadButton.A, R.string.action_select),
                        GamepadAction(GamepadButton.LB, R.string.hint_previous_category),
                        GamepadAction(GamepadButton.RB, R.string.hint_next_category),
                        GamepadAction(GamepadButton.B, R.string.back, onDismiss),
                    ),
                    forceVisible = true,
                    compact = useCompactLayout,
                )
            }
        }
    }
}

@Composable
private fun CategoryContent(
    title: String,
    description: String,
    options: List<AppMenuOption>,
    onOptionClick: (AppMenuOption) -> Unit,
    firstItemFocusRequester: FocusRequester,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        OptionGrid(
            options = options,
            onOptionClick = onOptionClick,
            firstItemFocusRequester = firstItemFocusRequester,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class OptionCategory {
    QUICK_ACTIONS,
    GAME_MANAGEMENT,
    CONTAINER,
    CLOUD_SAVES,
    HELP_INFO,
}

@Composable
private fun OptionGrid(
    options: List<AppMenuOption>,
    onOptionClick: (AppMenuOption) -> Unit,
    firstItemFocusRequester: FocusRequester,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(options) {
        scrollState.scrollTo(0)
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columnCount = if (compact || maxWidth < 540.dp) 1 else 2
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.chunked(columnCount).forEachIndexed { rowIndex, rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowOptions.forEachIndexed { columnIndex, option ->
                        val itemIndex = rowIndex * columnCount + columnIndex
                        OptionItem(
                            option = option,
                            onClick = { onOptionClick(option) },
                            focusRequester = if (itemIndex == 0) firstItemFocusRequester else null,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columnCount - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionItem(
    option: AppMenuOption,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs)),
        label = "optionScale",
    )

    val icon = getIconForOption(option.optionType)
    val isDestructive = option.optionType == AppOptionMenuType.Uninstall ||
        option.optionType == AppOptionMenuType.ResetToDefaults ||
        option.optionType == AppOptionMenuType.ResetDrm

    Row(
        modifier = modifier
            .heightIn(min = 66.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(10.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                isDestructive -> MaterialTheme.colorScheme.error
                isFocused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )

        Text(
            text = stringResource(option.optionType.title),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isFocused) FontWeight.Medium else FontWeight.Normal,
            color = when {
                isDestructive -> MaterialTheme.colorScheme.error
                isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun getIconForOption(type: AppOptionMenuType): ImageVector {
    return when (type) {
        AppOptionMenuType.StorePage -> Icons.AutoMirrored.Filled.OpenInNew
        AppOptionMenuType.CreateShortcut -> Icons.AutoMirrored.Filled.AddToHomeScreen
        AppOptionMenuType.ExportFrontend -> Icons.Default.Share
        AppOptionMenuType.RunContainer -> Icons.Default.PlayArrow
        AppOptionMenuType.EditContainer -> Icons.Default.Settings
        AppOptionMenuType.ResetToDefaults -> Icons.Default.RestartAlt
        AppOptionMenuType.GetSupport -> Icons.AutoMirrored.Filled.Help
        AppOptionMenuType.SubmitFeedback -> Icons.Default.Feedback
        AppOptionMenuType.ResetDrm -> Icons.Default.Key
        AppOptionMenuType.UseKnownConfig -> Icons.Default.Build
        AppOptionMenuType.BrowseCommunityConfigs -> Icons.Default.Search
        AppOptionMenuType.Uninstall -> Icons.Default.Delete
        AppOptionMenuType.VerifyFiles -> Icons.Default.VerifiedUser
        AppOptionMenuType.Update -> Icons.Default.Update
        AppOptionMenuType.MoveToExternalStorage -> Icons.Default.SdStorage
        AppOptionMenuType.MoveToInternalStorage -> Icons.Default.Storage
        AppOptionMenuType.ForceCloudSync -> Icons.Default.Sync
        AppOptionMenuType.BrowseOnlineSaves -> Icons.AutoMirrored.Filled.OpenInNew
        AppOptionMenuType.ForceDownloadRemote -> Icons.Default.CloudDownload
        AppOptionMenuType.ForceUploadLocal -> Icons.Default.CloudUpload
        AppOptionMenuType.FetchSteamGridDBImages -> Icons.Default.Image
        AppOptionMenuType.TestGraphics -> Icons.Default.Build
        AppOptionMenuType.PlayWithDiagnostics -> Icons.Default.BugReport
        AppOptionMenuType.ShareDiagnostics -> Icons.Default.Share
        AppOptionMenuType.ExportSupportBundle -> Icons.Default.Share
        AppOptionMenuType.ImportConfig -> Icons.Default.ArrowDownward
        AppOptionMenuType.ExportConfig -> Icons.Default.ArrowUpward
        AppOptionMenuType.ImportSaves -> Icons.Default.ArrowDownward
        AppOptionMenuType.ExportSaves -> Icons.Default.ArrowUpward
        AppOptionMenuType.ManageGameContent -> Icons.Default.Apps
        AppOptionMenuType.ManageWorkshop -> Icons.Default.Build
        AppOptionMenuType.ManageMods -> Icons.Default.Extension
        AppOptionMenuType.ChangeBranch -> Icons.AutoMirrored.Filled.CallSplit
    }
}

private fun groupOptions(options: List<AppMenuOption>): Map<OptionCategory, List<AppMenuOption>> {
    val quickActions = mutableListOf<AppMenuOption>()
    val gameManagement = mutableListOf<AppMenuOption>()
    val containerSettings = mutableListOf<AppMenuOption>()
    val cloudSaves = mutableListOf<AppMenuOption>()
    val helpInfo = mutableListOf<AppMenuOption>()

    options.forEach { option ->
        when (option.optionType) {
            // Quick Actions
            AppOptionMenuType.RunContainer,
            AppOptionMenuType.CreateShortcut,
            AppOptionMenuType.ExportFrontend,
            -> quickActions.add(option)

            // Game Management
            AppOptionMenuType.Uninstall,
            AppOptionMenuType.VerifyFiles,
            AppOptionMenuType.Update,
            AppOptionMenuType.MoveToExternalStorage,
            AppOptionMenuType.MoveToInternalStorage,
            AppOptionMenuType.ChangeBranch,
            AppOptionMenuType.ManageGameContent,
            AppOptionMenuType.ManageWorkshop,
            AppOptionMenuType.ManageMods,
            -> gameManagement.add(option)

            // Container Settings
            AppOptionMenuType.EditContainer,
            AppOptionMenuType.ResetToDefaults,
            AppOptionMenuType.ResetDrm,
            AppOptionMenuType.UseKnownConfig,
            AppOptionMenuType.BrowseCommunityConfigs,
            AppOptionMenuType.ImportConfig,
            AppOptionMenuType.ExportConfig,
            AppOptionMenuType.ImportSaves,
            AppOptionMenuType.ExportSaves,
            -> containerSettings.add(option)

            // Cloud Saves
            AppOptionMenuType.ForceCloudSync,
            AppOptionMenuType.BrowseOnlineSaves,
            AppOptionMenuType.ForceDownloadRemote,
            AppOptionMenuType.ForceUploadLocal,
            -> cloudSaves.add(option)

            // Help & Info
            AppOptionMenuType.StorePage,
            AppOptionMenuType.GetSupport,
            AppOptionMenuType.SubmitFeedback,
            AppOptionMenuType.FetchSteamGridDBImages,
            AppOptionMenuType.TestGraphics,
            AppOptionMenuType.PlayWithDiagnostics,
            AppOptionMenuType.ShareDiagnostics,
            AppOptionMenuType.ExportSupportBundle,
            -> helpInfo.add(option)
        }
    }

    return linkedMapOf(
        OptionCategory.QUICK_ACTIONS to quickActions,
        OptionCategory.GAME_MANAGEMENT to gameManagement,
        OptionCategory.CONTAINER to containerSettings,
        OptionCategory.CLOUD_SAVES to cloudSaves,
        OptionCategory.HELP_INFO to helpInfo,
    )
}
