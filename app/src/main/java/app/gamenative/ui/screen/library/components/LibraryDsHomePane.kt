package app.gamenative.ui.screen.library.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.gcds.GcdsHero
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.data.InstallProgress
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.utils.SteamGridDBIconProvider
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nintendo 3DS-style home layout for dual-screen handhelds (AYN Thor):
 * a big hero card of the focused game on top and a grid of small square
 * game icons below. Y cycles the icon scale (S/M/L).
 */
@Composable
internal fun LibraryDsHomePane(
    state: LibraryState,
    listState: LazyGridState,
    firstItemFocusRequester: FocusRequester? = null,
    focusTargetListIndex: Int? = null,
    onFocusedIndexChanged: (Int) -> Unit = {},
    onPageChange: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scaleStep by remember { mutableIntStateOf(PrefManager.dsHomeIconScale) }
    val focusedIndex = (focusTargetListIndex ?: 0).coerceIn(0, state.appInfoList.size.coerceAtLeast(1) - 1)
    val focusedItem = state.appInfoList.getOrNull(focusedIndex)

    LaunchedEffect(listState, state.appInfoList.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= state.appInfoList.lastIndex &&
                    state.appInfoList.size < state.totalAppsInFilter
                ) {
                    onPageChange(1)
                }
            }
    }

    val cellMinSize = when (scaleStep) {
        0 -> 72.dp
        2 -> 128.dp
        else -> 96.dp
    }

    Column(modifier = modifier.fillMaxSize()) {
        DsHeroCard(
            item = focusedItem,
            onClick = { focusedItem?.let { onNavigate(it.appId) } },
            installProgress = focusedItem?.let { state.installProgress[it.appId] },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.42f),
        )

        DsGameGrid(
            items = state.appInfoList,
            installProgressByAppId = state.installProgress,
            listState = listState,
            cellMinSize = cellMinSize,
            focusTargetIndex = focusTargetListIndex,
            firstItemFocusRequester = firstItemFocusRequester,
            onFocusedIndexChanged = onFocusedIndexChanged,
            onNavigate = onNavigate,
            onScaleCycle = {
                scaleStep = (scaleStep + 1) % 3
                PrefManager.dsHomeIconScale = scaleStep
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.58f),
        )
    }
}

/** Shared square-icon grid used by the DS_HOME pane and the second-display presentation. */
@Composable
internal fun DsGameGrid(
    items: List<LibraryItem>,
    listState: LazyGridState,
    cellMinSize: androidx.compose.ui.unit.Dp,
    focusTargetIndex: Int?,
    selectedIndex: Int? = focusTargetIndex,
    firstItemFocusRequester: FocusRequester?,
    onFocusedIndexChanged: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onScaleCycle: () -> Unit,
    showLabels: Boolean = false,
    cellAspectRatio: Float = 1f,
    preferSquareIcon: Boolean = true,
    installProgressByAppId: Map<String, InstallProgress> = emptyMap(),
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 72.dp),
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = cellMinSize),
        state = listState,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.ButtonY) {
                    onScaleCycle()
                    true
                } else {
                    false
                }
            },
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = items.size,
            key = { index -> items[index].appId },
        ) { index ->
            val item = items[index]
DsGameCell(
                item = item,
                onClick = { onNavigate(item.appId) },
                onFocused = { onFocusedIndexChanged(index) },
                selected = index == selectedIndex,
                showLabel = showLabels,
                cellAspectRatio = cellAspectRatio,
                preferSquareIcon = preferSquareIcon,
                installProgress = installProgressByAppId[item.appId],
                focusRequester = if (firstItemFocusRequester != null && index == focusTargetIndex) {
                    firstItemFocusRequester
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun DsHeroCard(
    item: LibraryItem?,
    onClick: () -> Unit,
    interactive: Boolean = true,
    installProgress: InstallProgress? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)

    Crossfade(
        targetState = item,
        animationSpec = motionSpec(tween(PluviaTheme.tokens.motionNormalMs)),
        label = "ds_hero_fade",
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    ) { target ->
        GcdsHero(
            modifier = Modifier.fillMaxSize(),
            interactionSource = interactionSource,
            onClick = onClick,
            interactive = interactive,
            shape = shape,
        ) {
            if (target != null) {
                val imageUrls by produceState(
                    initialValue = GridImageUrls("", ""),
                    key1 = target.appId,
                ) {
                    value = withContext(Dispatchers.IO) {
                        val resolved = getGridImageUrl(context, target, PaneType.GRID_HERO)
                        // Steam headers are only 460×215 and turn visibly soft
                        // on the upper display. Prefer the source hero when the
                        // current art is the stock header; custom/SteamGridDB
                        // artwork remains the deliberate first choice.
                        if (
                            target.heroImageUrl.isNotEmpty() &&
                            resolved.primary == target.headerImageUrl
                        ) {
                            GridImageUrls(
                                primary = target.heroImageUrl,
                                fallback = resolved.primary,
                            )
                        } else {
                            resolved
                        }
                    }
                }
                val imageUrl = imageUrls.primary.ifEmpty { imageUrls.fallback }
                if (imageUrl.isNotEmpty()) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { imageUrl },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.75f),
                                ),
                            ),
                        ),
                )
                Text(
                    text = target.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )

                installProgress?.let { progress ->
                    InstallProgressOverlay(
                        progress = progress,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .width(84.dp)
                            .padding(end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DsGameCell(
    item: LibraryItem,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    selected: Boolean = false,
    showLabel: Boolean = false,
    cellAspectRatio: Float = 1f,
    preferSquareIcon: Boolean = true,
    installProgress: InstallProgress? = null,
    focusRequester: FocusRequester? = null,
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isActive = isFocused || selected
    val shape = RoundedCornerShape(10.dp)
    // Focus scale: fast ease-out, no spring overshoot (console feel); snaps under reduced motion.
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs)),
        label = "dsCellScale",
    )

    val imageUrl by produceState(
        initialValue = "",
        key1 = item.appId,
    ) {
        value = withContext(Dispatchers.IO) {
            val sgdbIcon = if (preferSquareIcon && item.gameSource == GameSource.STEAM) {
                SteamGridDBIconProvider.iconForSteamApp(item.gameId)
            } else {
                null
            }
            sgdbIcon ?: getGridImageUrl(context, item, PaneType.GRID_CAPSULE)
                .let { it.primary.ifEmpty { it.fallback } }
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(cellAspectRatio)
            .scale(scale)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = item.name }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .then(
                if (selected && !isFocused) {
                    Modifier.border(
                        width = PluviaTheme.tokens.focusRingWidth,
                        color = MaterialTheme.colorScheme.tertiary,
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isActive,
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
        if (showLabel) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)),
                        ),
                    )
                    .padding(start = 9.dp, end = 9.dp, top = 18.dp, bottom = 8.dp),
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        installProgress?.let { progress ->
            InstallProgressOverlay(
                progress = progress,
                compact = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}
