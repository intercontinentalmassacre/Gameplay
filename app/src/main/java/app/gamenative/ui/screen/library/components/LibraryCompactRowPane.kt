package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.CompatibilityBadge
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.statsFor
import app.gamenative.ui.data.InstallProgress
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.utils.SteamGridDBIconProvider
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Switch-style home row over a blurred hero backdrop of the focused game.
 *
 * Works on every library tab:
 * - INSTALLED: square icon cells with the game name under the icon
 *   (left-aligned), row scrolls circularly left/right.
 * - ALL / store tabs: regular info cards (same look as the grid card view).
 */
@Composable
internal fun LibraryCompactRowPane(
    state: LibraryState,
    isInstalledTab: Boolean,
    firstItemFocusRequester: FocusRequester? = null,
    focusTargetListIndex: Int? = null,
    onFocusedIndexChanged: (Int) -> Unit = {},
    onNavigate: (String) -> Unit,
    onPageChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val items = state.appInfoList
    val itemCount = items.size
    val focusedIndex = (focusTargetListIndex ?: 0).coerceIn(0, itemCount.coerceAtLeast(1) - 1)
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    // Vertical grid tabs paginate as the user scrolls toward the end.
    LaunchedEffect(gridState, itemCount) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= itemCount - 1 &&
                    itemCount < state.totalAppsInFilter
                ) {
                    onPageChange(1)
                }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LibraryDynamicBackdrop(
            appInfo = items.getOrNull(focusedIndex),
            imageRefreshCounter = state.imageRefreshCounter,
            modifier = Modifier.fillMaxSize(),
        )

        // Top edge scrim so the translucent tab bar stays readable over the
        // hero backdrop (same gradient as the game page hero).
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.2f),
                            Color.Black.copy(alpha = 0f),
                        ),
                    ),
                ),
        )

        if (items.isEmpty() && !state.isLoading) {
            Text(
                text = stringResource(R.string.library_empty_installed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (items.isEmpty() && state.isLoading) {
            // Skeletons while the first filter pass is in flight
            if (isInstalledTab) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 76.dp, start = 20.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    repeat(5) {
                        Box(
                            modifier = Modifier
                                .width(132.dp)
                                .aspectRatio(1f),
                        ) {
                            GameSkeletonLoader(paneType = PaneType.GRID_CAPSULE)
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 76.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = false,
                ) {
                    items(12) {
                        GameSkeletonLoader(paneType = PaneType.GRID_CAPSULE)
                    }
                }
            }
        }

        // Bottom edge: soft translucent gradient (same chrome rule as the
        // hint bars) so the card row sits on fading content, not a flat band.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        )

        if (isInstalledTab) {
            // Single row, each game exactly once. Focus wraps at both ends.
            val coroutineScope = rememberCoroutineScope()

            fun wrapFocusTo(index: Int) {
                onFocusedIndexChanged(index)
                coroutineScope.launch {
                    listState.animateScrollToItem(index)
                    if (firstItemFocusRequester != null) {
                        kotlinx.coroutines.delay(60)
                        repeat(3) {
                            try {
                                firstItemFocusRequester.requestFocus()
                                return@launch
                            } catch (_: Exception) {
                                kotlinx.coroutines.delay(60)
                            }
                        }
                    }
                }
            }

            LazyRow(
                state = listState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 76.dp)
                    // Remember the focused cell when focus leaves and re-enters
                    // the row (jump under the last selected game, not to start).
                    .focusGroup()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.DirectionLeft && focusedIndex == 0 && itemCount > 1 -> {
                                wrapFocusTo(itemCount - 1)
                                true
                            }
                            event.key == Key.DirectionRight && focusedIndex == itemCount - 1 && itemCount > 1 -> {
                                wrapFocusTo(0)
                                true
                            }
                            else -> false
                        }
                    },
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(
                    count = itemCount,
                    key = { index -> items[index].appId },
                ) { index ->
                    val item = items[index]
                    CompactIconCell(
                        item = item,
                        compatibilityStatus = state.compatibilityMap[item.name],
                        onClick = { onNavigate(item.appId) },
                        onFocused = { onFocusedIndexChanged(index) },
                        installProgress = state.installProgress[item.appId],
                        focusRequester = if (firstItemFocusRequester != null && index == focusTargetListIndex) {
                            firstItemFocusRequester
                        } else {
                            null
                        },
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp)
                    .focusGroup(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 76.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = itemCount,
                    key = { index -> items[index].appId },
                ) { index ->
                    val item = items[index]
                    AppItem(
                        appInfo = item,
                        onClick = { onNavigate(item.appId) },
                        paneType = PaneType.GRID_CAPSULE,
                        onFocus = { onFocusedIndexChanged(index) },
                        modifier = if (firstItemFocusRequester != null && index == focusTargetListIndex) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                        imageRefreshCounter = state.imageRefreshCounter,
                        compatibilityStatus = state.compatibilityMap[item.name],
                        gameStats = state.statsFor(item),
                        installProgress = state.installProgress[item.appId],
                    )
                }
            }
        }
    }
}

/** Square icon + left-aligned name underneath, used on the INSTALLED tab. */
@Composable
private fun CompactIconCell(
    item: LibraryItem,
    compatibilityStatus: GameCompatibilityStatus?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    installProgress: InstallProgress? = null,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs)),
        label = "compactIconCellScale",
    )

    val imageUrl by produceState(
        initialValue = "",
        key1 = item.appId,
    ) {
        value = withContext(Dispatchers.IO) {
            val sgdbIcon = if (item.gameSource == GameSource.STEAM) {
                SteamGridDBIconProvider.iconForSteamApp(item.gameId)
            } else {
                null
            }
            sgdbIcon ?: getGridImageUrl(context, item, PaneType.GRID_CAPSULE)
                .let { it.primary.ifEmpty { it.fallback } }
        }
    }

    Column(
        modifier = Modifier
            .width(132.dp)
            .scale(scale)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged { if (it.isFocused) onFocused() },
    ) {
        Box(
            modifier = Modifier
                .width(132.dp)
                .aspectRatio(1f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .focusRing(interactionSource, shape, width = 2.dp)
                .selectable(
                    selected = isFocused,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (imageUrl.isNotEmpty()) {
                CoilImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModel = { imageUrl },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                )
            }
            if (compatibilityStatus != null) {
                CompatibilityBadge(
                    status = compatibilityStatus,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isFocused) {
                Color.White
            } else {
                Color.White.copy(alpha = 0.82f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )

        installProgress?.let { progress ->
            InstallProgressOverlay(
                progress = progress,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
    }
}
