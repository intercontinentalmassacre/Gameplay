package app.gamenative.ui.component.dialog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import app.gamenative.ui.component.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import app.gamenative.ui.component.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomMediaUtils
import app.gamenative.utils.GameImageUtils
import app.gamenative.utils.bustCache
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * Media management tab: pick or reset custom artwork (logo, icon, header,
 * capsule, hero) per game. Custom images take priority over SteamGridDB and
 * Steam artwork everywhere the game is rendered.
 * Ported from upstream GameNative PR #297.
 */
@Composable
fun MediaTabContent(
    gameId: Int?,
    appId: String?,
    mediaHeroUrl: String?,
    mediaLogoUrl: String?,
    mediaCapsuleUrl: String?,
    mediaHeaderUrl: String?,
    mediaIconUrl: String?,
) {
    if (gameId == null || appId.isNullOrEmpty()) {
        SettingsCenteredLabel(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.media_no_grid_hero)) },
        )
        return
    }

    // Observe global media change version to refresh previews instantly
    val mediaVersion by CustomMediaUtils.mediaVersionFlow.collectAsState(initial = 0)

    val gameSource = if (appId.startsWith("CUSTOM_GAME_")) GameSource.CUSTOM_GAME else GameSource.STEAM
    val libraryItem = remember(appId) {
        LibraryItem(
            appId = appId,
            name = "",
            gameSource = gameSource,
        )
    }

    Column {
        // LOGO ---------------------------------------------
        val currentLogoModel: Any? = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(libraryItem, "logo", mediaLogoUrl)
        }
        MediaSection(
            titleRes = R.string.media_logo_title,
            descriptionRes = R.string.media_logo_hint,
            noMediaTitleRes = R.string.media_no_logo,
            gameId = gameId,
            mediaVersion = mediaVersion,
            currentModel = currentLogoModel,
            placeholderRes = R.drawable.testliblogo,
            imageModifier = Modifier
                .widthIn(min = 150.dp, max = 300.dp)
                .heightIn(max = 100.dp),
            imageContentScale = ContentScale.Fit,
            hasCustomMedia = CustomMediaUtils::hasCustomLogo,
            onPickMedia = { ctx, gid, uri -> CustomMediaUtils.saveCustomLogo(ctx, gid, uri) },
            onResetMedia = CustomMediaUtils::resetCustomLogo,
        )

        // ICON ---------------------------------------------
        val currentIconModel: Any? = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(libraryItem, "icon", mediaIconUrl)
        }
        MediaSection(
            titleRes = R.string.media_icon_title,
            descriptionRes = R.string.media_icon_hint,
            noMediaTitleRes = R.string.media_no_icon,
            gameId = gameId,
            mediaVersion = mediaVersion,
            currentModel = currentIconModel,
            placeholderRes = R.drawable.ic_logo_color,
            imageModifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp)),
            imageContentScale = ContentScale.Fit,
            hasCustomMedia = CustomMediaUtils::hasCustomIcon,
            onPickMedia = { ctx, gid, uri -> CustomMediaUtils.saveCustomIcon(ctx, gid, uri) },
            onResetMedia = CustomMediaUtils::resetCustomIcon,
        )

        // HEADER ---------------------------------------------
        val currentHeaderModel: Any? = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(libraryItem, "header", mediaHeaderUrl)
        }
        MediaSection(
            titleRes = R.string.media_header_title,
            descriptionRes = R.string.media_header_hint,
            noMediaTitleRes = R.string.media_no_header,
            gameId = gameId,
            mediaVersion = mediaVersion,
            currentModel = currentHeaderModel,
            placeholderRes = R.drawable.testhero,
            imageModifier = Modifier
                .widthIn(min = 200.dp, max = 400.dp)
                .height(250.dp),
            imageContentScale = ContentScale.Crop,
            hasCustomMedia = CustomMediaUtils::hasCustomHeader,
            onPickMedia = { ctx, gid, uri -> CustomMediaUtils.saveCustomHeader(ctx, gid, uri) },
            onResetMedia = CustomMediaUtils::resetCustomHeader,
        )

        // Grid CAPSULE ---------------------------------------------
        val currentCapsuleModel: Any? = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(libraryItem, "grid_capsule", mediaCapsuleUrl)
        }
        MediaSection(
            titleRes = R.string.media_grid_capsule_title,
            descriptionRes = R.string.media_grid_capsule_hint,
            noMediaTitleRes = R.string.media_no_grid_capsule,
            gameId = gameId,
            mediaVersion = mediaVersion,
            currentModel = currentCapsuleModel,
            placeholderRes = R.drawable.testhero,
            imageModifier = Modifier
                .widthIn(min = 150.dp, max = 250.dp)
                .aspectRatio(2 / 3f)
                .clip(RoundedCornerShape(3.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(3.dp),
                ),
            imageContentScale = ContentScale.Crop,
            hasCustomMedia = CustomMediaUtils::hasCustomCapsule,
            onPickMedia = { ctx, gid, uri -> CustomMediaUtils.saveCustomCapsule(ctx, gid, uri) },
            onResetMedia = CustomMediaUtils::resetCustomCapsule,
        )

        // Grid HERO ---------------------------------------------
        val currentHeroModel: Any? = remember(mediaVersion, libraryItem) {
            GameImageUtils.getGameImage(libraryItem, "grid_hero", mediaHeroUrl)
        }
        MediaSection(
            titleRes = R.string.media_grid_hero_title,
            descriptionRes = R.string.media_grid_hero_hint,
            noMediaTitleRes = R.string.media_no_grid_hero,
            gameId = gameId,
            mediaVersion = mediaVersion,
            currentModel = currentHeroModel,
            placeholderRes = R.drawable.testhero,
            imageModifier = Modifier
                .widthIn(min = 150.dp, max = 250.dp)
                .aspectRatio(460 / 215f)
                .clip(RoundedCornerShape(3.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(3.dp),
                ),
            imageContentScale = ContentScale.Crop,
            hasCustomMedia = CustomMediaUtils::hasCustomHero,
            onPickMedia = { ctx, gid, uri -> CustomMediaUtils.saveCustomHero(ctx, gid, uri) },
            onResetMedia = CustomMediaUtils::resetCustomHero,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Reusable composable for media management sections (Logo, Icon, Hero, Capsule, Header).
 * Handles displaying the media, pick/reset actions, and all UI elements.
 */
@Composable
private fun MediaSection(
    titleRes: Int,
    descriptionRes: Int,
    noMediaTitleRes: Int,
    gameId: Int,
    mediaVersion: Int,
    currentModel: Any?,
    placeholderRes: Int,
    imageModifier: Modifier,
    imageContentScale: ContentScale,
    hasCustomMedia: (Int) -> Boolean,
    onPickMedia: (android.content.Context, Int, Uri) -> Boolean,
    onResetMedia: (Int) -> Unit,
) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val hasModel = when (currentModel) {
            is String -> currentModel.isNotBlank()
            is Uri -> true
            else -> false
        }
        if (hasModel) {
            CoilImage(
                modifier = imageModifier,
                imageModel = { bustCache(currentModel, mediaVersion) },
                imageOptions = ImageOptions(contentScale = imageContentScale),
                previewPlaceholder = painterResource(placeholderRes),
            )
        } else {
            SettingsCenteredLabel(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(noMediaTitleRes)) },
                subtitle = { Text(text = stringResource(R.string.media_choose_media)) },
            )
        }
    }

    Text(
        text = stringResource(descriptionRes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    val context = LocalContext.current
    val isCustom = remember(mediaVersion, gameId) { hasCustomMedia(gameId) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val ok = onPickMedia(context, gameId, uri)
            SnackbarManager.show(
                if (ok) {
                    context.getString(R.string.media_updated, context.getString(titleRes))
                } else {
                    context.getString(R.string.media_update_failed, context.getString(titleRes).lowercase())
                },
            )
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        Button(onClick = { picker.launch("image/*") }) {
            Text(stringResource(R.string.media_choose_image))
        }
        if (isCustom) {
            OutlinedButton(
                onClick = {
                    onResetMedia(gameId)
                    SnackbarManager.show(context.getString(R.string.media_reverted))
                },
            ) {
                Text(stringResource(R.string.media_remove_custom_image))
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}
