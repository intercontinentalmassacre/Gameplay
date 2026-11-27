package app.gamenative.ui.screen.library.components

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.InstallProgress
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.ui.util.ListItemImage
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.CustomMediaUtils
import app.gamenative.utils.CoverCache
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grid card for Hero/Capsule layout views.
 */
@Composable
internal fun GridViewCard(
    modifier: Modifier,
    appInfo: LibraryItem,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    isFocused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    scale: Float,
    paneType: PaneType,
    imageRefreshCounter: Long,
    showMissingArtwork: Boolean,
    imageAlpha: Float,
    onImageLoadFailed: () -> Unit,
    showFocusGlow: Boolean,
    context: Context,
    installProgress: InstallProgress? = null,
) {
    val aspectRatio = if (paneType == PaneType.GRID_CAPSULE || paneType == PaneType.INSTALLED_COMPACT) 2f / 3f else 460f / 215f
    val isCapsule = (paneType == PaneType.GRID_CAPSULE || paneType == PaneType.INSTALLED_COMPACT)
    val cardContentBottomPadding = if (isCapsule) 12.dp else 8.dp
    val bottomGradientHeight = if (isCapsule) 80.dp else 56.dp
    val cardShape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val interactionSource = remember { MutableInteractionSource() }
    val isItemFocused by interactionSource.collectIsFocusedAsState()
    val focusElevation by animateDpAsState(
        targetValue = if (isItemFocused) PluviaTheme.tokens.elevationFocus else 0.dp,
        animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs)),
        label = "cardFocusElevation",
    )

    LaunchedEffect(isItemFocused) {
        onFocusChanged(isItemFocused)
        if (isItemFocused) onFocus()
    }

    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .scale(scale),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .shadow(focusElevation, cardShape)
                .focusRing(interactionSource, cardShape)
                .clickable(
                    onClick = onClick,
                    interactionSource = interactionSource,
                    indication = null,
                ),
            shape = cardShape,
            colors = CardDefaults.cardColors(
                containerColor = Color.Transparent,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Game image (primary + optional fallback for Steam header/hero)
                val mediaVersion by CustomMediaUtils.mediaVersionFlow.collectAsState(initial = 0)
                val imageUrls = if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
                    produceState(
                        initialValue = GridImageUrls("", ""),
                        key1 = appInfo.appId,
                        key2 = paneType,
                        key3 = "$imageRefreshCounter|$mediaVersion",
                    ) {
                        value = withContext(Dispatchers.IO) {
                            getGridImageUrl(context, appInfo, paneType)
                        }
                    }.value
                } else {
                    remember(appInfo.appId, paneType, imageRefreshCounter, mediaVersion) {
                        getGridImageUrl(context, appInfo, paneType)
                    }
                }

                var currentImageUrl by remember(
                    imageUrls.primary,
                    imageUrls.fallback,
                    appInfo.appId,
                    imageRefreshCounter,
                    mediaVersion,
                ) {
                    mutableStateOf(imageUrls.primary)
                }

                if (isCapsule && currentImageUrl.isNotEmpty()) {
                    CapsuleFallbackBackdrop(
                        imageUrl = currentImageUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                val gridHeroZoom = if (!isCapsule && appInfo.gridHeroImageScale != 1f) {
                    Modifier.graphicsLayer {
                        scaleX = appInfo.gridHeroImageScale
                        scaleY = appInfo.gridHeroImageScale
                        transformOrigin = TransformOrigin.Center
                    }
                } else {
                    Modifier
                }

                ListItemImage(
                    modifier = Modifier.fillMaxSize(),
                    imageModifier = Modifier
                        .fillMaxSize()
                        .alpha(imageAlpha)
                        .then(gridHeroZoom),
                    contentScale = getGridContentScale(paneType),
                    image = { currentImageUrl },
                    onFailure = {
                        if (imageUrls.fallback.isNotEmpty() && currentImageUrl == imageUrls.primary) {
                            currentImageUrl = imageUrls.fallback
                        } else {
                            onImageLoadFailed()
                        }
                    },
                )

                // Show the game name in place of missing art.
                if (showMissingArtwork) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = appInfo.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                // Gradient overlay at bottom for title
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomGradientHeight)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f),
                                ),
                            ),
                        ),
                )

                // Keep the game name and live progress legible over the cover.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = cardContentBottomPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (!showMissingArtwork) {
                        Text(
                            text = appInfo.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White,
                            maxLines = if (isCapsule) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (appInfo.isRecommended && appInfo.recStoreCard && appInfo.recPrice != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            appInfo.recBasePrice?.let { base ->
                                Text(
                                    text = base,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        textDecoration = TextDecoration.LineThrough,
                                    ),
                                    color = Color.White.copy(alpha = 0.6f),
                                )
                            }
                            Text(
                                text = appInfo.recPrice,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                modifier = if (appInfo.recBasePrice != null) Modifier.padding(start = 6.dp) else Modifier,
                            )
                        }
                    }

                    // Live install progress: thin determinate/indeterminate bar + stage label
                    installProgress?.let { progress ->
                        InstallProgressOverlay(progress = progress)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapsuleFallbackBackdrop(
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        CoilImage(
            modifier = Modifier
                .fillMaxSize()
                .blur(8.dp),
            imageModel = { imageUrl },
            imageOptions = ImageOptions(
                contentScale = ContentScale.Crop,
                contentDescription = null,
            ),
            loading = {},
            failure = {},
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.28f),
                            0.45f to Color.Black.copy(alpha = 0.12f),
                            1.0f to Color.Black.copy(alpha = 0.34f),
                        ),
                    ),
                ),
        )
    }
}

/**
 * Primary and optional fallback image URL for grid view (e.g. Steam header -> hero).
 */
internal data class GridImageUrls(val primary: String, val fallback: String = "")

private fun getGridContentScale(paneType: PaneType): ContentScale {
    return when (paneType) {
        // Hero and capsule both show cover art that should fill the slot. Capsule art is
        // close to but not always exactly 2:3 (e.g. GOG covers are ~0.71), so cropping the
        // overflow looks better than letterboxing it against the blurred backdrop.
        PaneType.GRID_HERO, PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT -> ContentScale.Crop
        else -> ContentScale.Fit
    }
}

/**
 * Gets the appropriate image URL(s) for a game in grid view.
 * Matches master: source-specific URLs, Steam uses headerImageUrl with heroImageUrl fallback.
 */
internal fun getGridImageUrl(
    context: Context,
    appInfo: LibraryItem,
    paneType: PaneType,
): GridImageUrls {
    fun findSteamGridDBImage(imageType: String): String? {
        if (appInfo.gameSource == GameSource.CUSTOM_GAME) {
            val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
            gameFolderPath?.let { path ->
                val folder = File(path)
                val imageFile = folder.listFiles()?.firstOrNull { file ->
                    file.name.startsWith("steamgriddb_$imageType") &&
                        (
                            file.name.endsWith(".png", ignoreCase = true) ||
                                file.name.endsWith(".jpg", ignoreCase = true) ||
                                file.name.endsWith(".webp", ignoreCase = true)
                            )
                }
                return imageFile?.let { android.net.Uri.fromFile(it).toString() }
            }
        }
        return null
    }

    // Custom media (user-picked artwork) takes priority over every other source.
    val customMediaUri = if (appInfo.gameId != 0) {
        when (paneType) {
            PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT -> CustomMediaUtils.getCustomCapsuleUri(appInfo.gameId)
            PaneType.GRID_HERO -> CustomMediaUtils.getCustomHeroUri(appInfo.gameId)
            else -> CustomMediaUtils.getCustomHeaderUri(appInfo.gameId)
        }
    } else {
        null
    }

    val cachedUri = CoverCache.fileUri(appInfo.appId, when (paneType) {
        PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT -> CoverCache.Type.GRID_CAPSULE
        PaneType.GRID_HERO -> CoverCache.Type.GRID_HERO
        else -> CoverCache.Type.HEADER
    })
    if (cachedUri != null) {
        return if (customMediaUri == null) {
            GridImageUrls(primary = cachedUri)
        } else {
            GridImageUrls(primary = customMediaUri.toString(), fallback = cachedUri)
        }
    }

    val baseUrls = when (appInfo.gameSource) {
        GameSource.CUSTOM_GAME -> {
            val primary = when (paneType) {
                PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT ->
                    // Capsule (vertical): user "coverv"/"cover" wins over SteamGridDB capsule.
                    CustomGameScanner.findCapsuleCoverForCustomGame(appInfo.appId)
                        ?: findSteamGridDBImage("grid_capsule")
                        ?: appInfo.capsuleImageUrl
                PaneType.GRID_HERO ->
                    // Hero (horizontal): user "coverh"/"cover" wins over SteamGridDB hero.
                    CustomGameScanner.findHeroCoverForCustomGame(appInfo.appId)
                        ?: findSteamGridDBImage("grid_hero")
                        ?: appInfo.headerImageUrl
                else -> {
                    // Default/carousel banner is also a horizontal hero view.
                    val heroCover = CustomGameScanner.findHeroCoverForCustomGame(appInfo.appId)
                    val gameFolderPath = CustomGameScanner.getFolderPathFromAppId(appInfo.appId)
                    val heroUrl = gameFolderPath?.let { path ->
                        val folder = File(path)
                        val heroFile = folder.listFiles()?.firstOrNull { file ->
                            file.name.startsWith("steamgriddb_hero") &&
                                !file.name.contains("grid") &&
                                (
                                    file.name.endsWith(".png", ignoreCase = true) ||
                                        file.name.endsWith(".jpg", ignoreCase = true) ||
                                        file.name.endsWith(".webp", ignoreCase = true)
                                    )
                        }
                        heroFile?.let { android.net.Uri.fromFile(it).toString() }
                    }
                    heroCover ?: heroUrl ?: appInfo.headerImageUrl
                }
            }
            GridImageUrls(primary = primary)
        }

        GameSource.GOG, GameSource.EPIC, GameSource.AMAZON, GameSource.ITCH -> {
            val primary = when (paneType) {
                PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT -> appInfo.capsuleImageUrl.ifEmpty { appInfo.iconHash }
                else -> appInfo.headerImageUrl.ifEmpty {
                    appInfo.heroImageUrl.ifEmpty { appInfo.iconHash }
                }
            }
            val fallback = when {
                paneType == PaneType.GRID_CAPSULE || paneType == PaneType.INSTALLED_COMPACT ->
                    appInfo.iconHash.takeIf { it.isNotEmpty() && it != primary } ?: ""
                appInfo.heroImageUrl.isNotEmpty() && appInfo.heroImageUrl != primary ->
                    appInfo.heroImageUrl
                appInfo.iconHash.isNotEmpty() && appInfo.iconHash != primary ->
                    appInfo.iconHash
                else -> ""
            }
            GridImageUrls(primary = primary, fallback = fallback)
        }

        GameSource.STEAM -> when (paneType) {
            PaneType.GRID_CAPSULE, PaneType.INSTALLED_COMPACT ->
                GridImageUrls(primary = appInfo.capsuleImageUrl)
            else ->
                GridImageUrls(
                    primary = appInfo.headerImageUrl,
                    fallback = appInfo.heroImageUrl,
                )
        }
        else -> GridImageUrls(primary = appInfo.iconHash)
    }

    return if (customMediaUri != null) {
        GridImageUrls(primary = customMediaUri.toString(), fallback = baseUrls.primary)
    } else {
        baseUrls
    }
}
