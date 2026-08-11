package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.rememberIsDualScreenDevice
import app.gamenative.ui.util.WindowWidthClass
import app.gamenative.ui.util.rememberWindowWidthClass

/**
 * Controller-first library navigation with a restrained selection rail.
 * Adapts to screen width
 */
@Composable
fun LibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit = {},
    onNextTab: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val widthClass = rememberWindowWidthClass()

    when (widthClass) {
        WindowWidthClass.COMPACT -> CompactLibraryTabBar(
            currentTab = currentTab,
            tabs = tabs,
            tabCounts = tabCounts,
            currentView = currentView,
            onViewChanged = onViewChanged,
            onTabSelected = onTabSelected,
            onOptionsClick = onOptionsClick,
            onSearchClick = onSearchClick,
            onAddGameClick = onAddGameClick,
            onMenuClick = onMenuClick,
            onNavigateDownToGrid = onNavigateDownToGrid,
            onPreviousTab = onPreviousTab,
            onNextTab = onNextTab,
            modifier = modifier,
        )

        else -> ExpandedLibraryTabBar(
            currentTab = currentTab,
            tabs = tabs,
            tabCounts = tabCounts,
            currentView = currentView,
            onViewChanged = onViewChanged,
            onTabSelected = onTabSelected,
            onOptionsClick = onOptionsClick,
            onSearchClick = onSearchClick,
            onAddGameClick = onAddGameClick,
            onMenuClick = onMenuClick,
            onNavigateDownToGrid = onNavigateDownToGrid,
            onPreviousTab = onPreviousTab,
            onNextTab = onNextTab,
            modifier = modifier,
        )
    }
}

/**
 * Compact tab bar for narrow screens.
 * Centered tabs with action buttons for Options, Search, Add Game, and Menu.
 */
@Composable
private fun CompactLibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = tabs.indexOf(currentTab)
    val scrollState = rememberScrollState()
    val tabPositions = remember { mutableStateMapOf<Int, Float>() }
    val tabWidths = remember { mutableStateMapOf<Int, Float>() }

    LaunchedEffect(currentTab) {
        val pos = tabPositions[currentIndex] ?: return@LaunchedEffect
        val width = tabWidths[currentIndex] ?: return@LaunchedEffect
        val targetCenter = (pos + width / 2).toInt()
        val viewportCenter = scrollState.viewportSize / 2
        scrollState.animateScrollTo((targetCenter - viewportCenter).coerceAtLeast(0))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    ),
                ),
            )
            .padding(top = 8.dp, bottom = 12.dp, start = 8.dp, end = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onNavigateDownToGrid()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_L1 -> {
                                onPreviousTab()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_R1 -> {
                                onNextTab()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactIconButton(
                icon = Icons.Default.Tune,
                contentDescription = stringResource(R.string.options),
                onClick = onOptionsClick,
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .horizontalScroll(scrollState)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = tab == currentTab
                    val tabInteractionSource = remember { MutableInteractionSource() }
                    val isTabFocused by tabInteractionSource.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                tabPositions[index] = coordinates.positionInParent().x
                                tabWidths[index] = coordinates.size.width.toFloat()
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                                    isTabFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else -> Color.Transparent
                                },
                            )
                            .focusRing(tabInteractionSource, RoundedCornerShape(16.dp), width = 2.dp)
                            .selectable(
                                selected = isSelected,
                                interactionSource = tabInteractionSource,
                                indication = null,
                                onClick = { onTabSelected(tab) },
                            )
                            .padding(horizontal = if (tab.icon != null) 8.dp else 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val count = tabCounts[tab]
                        val tabColor = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            isTabFocused -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        }
                        if (tab.icon != null) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelResId),
                                tint = tabColor,
                                modifier = Modifier.size(18.dp),
                            )
                        } else {
                            val label = if (count != null && count > 0) {
                                stringResource(R.string.library_tab_with_count, stringResource(tab.labelResId), count)
                            } else {
                                stringResource(tab.labelResId)
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = tabColor,
                            )
                        }
                    }
                }
            }

            val isDualScreenDevice = rememberIsDualScreenDevice()
            CompactIconButton(
                icon = currentView.libraryViewIcon(),
                contentDescription = stringResource(R.string.library_layout_title),
                onClick = { onViewChanged(currentView.nextLibraryView(isDualScreen = isDualScreenDevice)) },
            )
            CompactIconButton(
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                onClick = onSearchClick,
            )
            CompactIconButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.action_add_game),
                onClick = onAddGameClick,
            )
            CompactIconButton(
                icon = Icons.Default.Menu,
                contentDescription = stringResource(R.string.menu),
                onClick = onMenuClick,
            )
        }
    }
}

/**
 * Simple icon button for compact tab bar.
 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .selectable(
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun PaneType.nextLibraryView(isDualScreen: Boolean = false): PaneType = when (this) {
    PaneType.LIST, PaneType.CAROUSEL, PaneType.UNDECIDED -> PaneType.GRID_CAPSULE
    PaneType.GRID_CAPSULE -> PaneType.INSTALLED_COMPACT
    PaneType.INSTALLED_COMPACT -> PaneType.GRID_HERO
    PaneType.GRID_HERO -> if (isDualScreen) PaneType.DS_HOME else PaneType.CAROUSEL
    PaneType.DS_HOME -> PaneType.CAROUSEL
}

private fun PaneType.libraryViewIcon(): ImageVector = when (this) {
    PaneType.LIST, PaneType.UNDECIDED, PaneType.GRID_CAPSULE -> Icons.Default.PhotoAlbum
    PaneType.INSTALLED_COMPACT -> Icons.Default.Apps
    PaneType.GRID_HERO -> Icons.Default.PhotoSizeSelectActual
    PaneType.CAROUSEL -> Icons.Default.ViewCarousel
    PaneType.DS_HOME -> Icons.Default.Gamepad
}

/**
 * Expanded tab bar for wide screens (landscape phone, tablet).
 */
@Composable
private fun ExpandedLibraryTabBar(
    currentTab: LibraryTab,
    tabs: List<LibraryTab>,
    tabCounts: Map<LibraryTab, Int>,
    currentView: PaneType,
    onViewChanged: (PaneType) -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onOptionsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    onMenuClick: () -> Unit,
    onNavigateDownToGrid: () -> Unit,
    onPreviousTab: () -> Unit,
    onNextTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentIndex = tabs.indexOf(currentTab)
    val scrollState = rememberScrollState()

    val tabPositions = remember { mutableStateMapOf<Int, Float>() }
    val tabWidths = remember { mutableStateMapOf<Int, Float>() }

    val density = LocalDensity.current

    val indicatorOffset by animateDpAsState(
        targetValue = with(density) { (tabPositions[currentIndex] ?: 0f).toDp() },
        animationSpec = tween(durationMillis = 180),
        label = "indicatorOffset",
    )

    val indicatorWidth by animateDpAsState(
        targetValue = with(density) { (tabWidths[currentIndex] ?: 80f).toDp() },
        animationSpec = tween(durationMillis = 180),
        label = "indicatorWidth",
    )

    LaunchedEffect(currentTab) {
        val pos = tabPositions[currentIndex] ?: return@LaunchedEffect
        val width = tabWidths[currentIndex] ?: return@LaunchedEffect
        val targetCenter = (pos + width / 2).toInt()
        val viewportCenter = scrollState.viewportSize / 2
        scrollState.animateScrollTo((targetCenter - viewportCenter).coerceAtLeast(0))
    }

    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0.88f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                    ),
                ),
            )
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusGroup()
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                onNavigateDownToGrid()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_L1 -> {
                                onPreviousTab()
                                true
                            }
                            KeyEvent.KEYCODE_BUTTON_R1 -> {
                                onNextTab()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconActionButton(
                icon = Icons.Default.Tune,
                contentDescription = stringResource(R.string.options),
                onClick = onOptionsClick,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // A thin selection rail keeps the navigation calm while remaining legible at TV distance.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset { IntOffset(indicatorOffset.roundToPx(), 0) }
                        .width(indicatorWidth)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        TabItem(
                            tab = tab,
                            count = tabCounts[tab],
                            isSelected = tab == currentTab,
                            onClick = { onTabSelected(tab) },
                            onPositioned = { position, width ->
                                tabPositions[index] = position
                                tabWidths[index] = width
                            },
                        )
                    }
                }
            }

            val isDualScreenDevice = rememberIsDualScreenDevice()
            IconActionButton(
                icon = currentView.libraryViewIcon(),
                contentDescription = stringResource(R.string.library_layout_title),
                onClick = { onViewChanged(currentView.nextLibraryView(isDualScreen = isDualScreenDevice)) },
            )

            IconActionButton(
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.search),
                onClick = onSearchClick,
            )

            IconActionButton(
                icon = Icons.Default.Add,
                contentDescription = stringResource(R.string.action_add_game),
                onClick = onAddGameClick,
            )

            IconActionButton(
                icon = Icons.Default.Menu,
                contentDescription = stringResource(R.string.menu),
                onClick = onMenuClick,
            )
        }
    }
}

@Composable
private fun IconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "iconButtonScale",
    )

    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.7f,
        animationSpec = tween(durationMillis = 160),
        label = "iconButtonAlpha",
    )

    Box(
        modifier = modifier
            // Both the focus scale and alpha go in a SINGLE graphicsLayer above the ring. Previously
            // .scale (above) and .alpha (below) were two separate animating layers sandwiching the
            // focusRing draw node, which made the ring flicker off ~100ms after focus. One stable
            // layer wrapping clip/background/focusRing fixes it.
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                },
            )
            .focusRing(interactionSource, CircleShape, width = 2.dp)
            .selectable(
                // These are actions, not toggles; feeding isFocused back as `selected` caused an
                // extra recomposition on every focus change.
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun TabItem(
    tab: LibraryTab,
    count: Int?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPositioned: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val textAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            isFocused -> 0.9f
            else -> 0.6f
        },
        animationSpec = tween(durationMillis = 160),
        label = "textAlpha",
    )

    val label = if (count != null && count > 0) {
        stringResource(R.string.library_tab_with_count, stringResource(tab.labelResId), count)
    } else {
        stringResource(tab.labelResId)
    }

    Box(
        modifier = modifier
            // Preserve a large controller target without turning every tab into a decorative pill.
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
            )
            .focusRing(interactionSource, RoundedCornerShape(8.dp), width = 2.dp)
            .onGloballyPositioned { coordinates ->
                onPositioned(
                    coordinates.positionInParent().x,
                    coordinates.size.width.toFloat(),
                )
            }
            .selectable(
                selected = isSelected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (tab.icon != null) 10.dp else 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (tab.icon != null) {
            Icon(
                imageVector = tab.icon,
                contentDescription = stringResource(tab.labelResId),
                tint = when {
                    isSelected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                },
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha)
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun Preview_LibraryTabBar() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            LibraryTabBar(
                currentTab = LibraryTab.ALL,
                tabs = listOf(LibraryTab.INSTALLED, LibraryTab.ALL, LibraryTab.STEAM, LibraryTab.GOG, LibraryTab.EPIC, LibraryTab.LOCAL),
                tabCounts = mapOf(
                    LibraryTab.ALL to 42,
                    LibraryTab.STEAM to 30,
                    LibraryTab.GOG to 8,
                    LibraryTab.EPIC to 4,
                    LibraryTab.LOCAL to 3,
                ),
                currentView = PaneType.GRID_HERO,
                onViewChanged = {},
                onTabSelected = {},
                onOptionsClick = {},
                onSearchClick = {},
                onAddGameClick = {},
                onMenuClick = {},
                onNavigateDownToGrid = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A1A)
@Composable
private fun Preview_LibraryTabBar_Steam() {
    PluviaTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            LibraryTabBar(
                currentTab = LibraryTab.STEAM,
                tabs = listOf(LibraryTab.INSTALLED, LibraryTab.ALL, LibraryTab.STEAM, LibraryTab.GOG, LibraryTab.EPIC, LibraryTab.LOCAL),
                tabCounts = mapOf(
                    LibraryTab.ALL to 42,
                    LibraryTab.STEAM to 30,
                    LibraryTab.GOG to 8,
                    LibraryTab.EPIC to 4,
                    LibraryTab.LOCAL to 3,
                ),
                currentView = PaneType.GRID_HERO,
                onViewChanged = {},
                onTabSelected = {},
                onOptionsClick = {},
                onSearchClick = {},
                onAddGameClick = {},
                onMenuClick = {},
                onNavigateDownToGrid = {},
            )
        }
    }
}
