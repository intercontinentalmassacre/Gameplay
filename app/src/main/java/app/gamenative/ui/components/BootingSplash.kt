package app.gamenative.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.data.MainState
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.isReduceMotionEnabled
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/** Full-screen console launch surface shown while a game or container is prepared. */
@Composable
fun BootingSplash(
    state: MainState,
    onShowGameScreen: () -> Unit,
    progress: Float = -1f,
) {
    val heroImageUrl = state.bootingSplashHeroImageUrl
    AnimatedVisibility(
        visible = state.showBootingSplash,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
    ) {
        var heroImageFailed by remember(heroImageUrl) { mutableStateOf(false) }
        val useHeroBackdrop = heroImageUrl.isNotBlank() && !heroImageFailed

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (useHeroBackdrop) {
                val restrainedArtwork = remember {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.28f) })
                }
                CoilImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.5f),
                    imageModel = { heroImageUrl },
                    imageOptions = ImageOptions(
                        contentScale = ContentScale.Crop,
                        contentDescription = null,
                        colorFilter = restrainedArtwork,
                    ),
                    loading = {},
                    failure = { heroImageFailed = true },
                    previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.97f),
                            0.6f to MaterialTheme.colorScheme.background.copy(alpha = 0.78f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0.4f),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.7f),
                            0.35f to Color.Transparent,
                            0.7f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 40.dp, vertical = 30.dp)
                    .widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = state.bootingSplashText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LaunchProgressBar(
                        progress = progress,
                        modifier = Modifier
                            .weight(1f)
                            .height(2.dp),
                    )
                    if (progress >= 0f) {
                        Text(
                            text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (state.bootingSplashTakingLonger) {
                    Text(
                        text = stringResource(R.string.game_startup_taking_longer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (state.canRevealGameScreen) {
                        TextButton(onClick = onShowGameScreen) {
                            Text(stringResource(R.string.game_startup_show_screen))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val isIndeterminate = progress < 0f
    val phase = if (isIndeterminate) {
        if (isReduceMotionEnabled()) {
            0.5f
        } else {
            val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "launchProgress")
            val value by transition.animateFloat(
                initialValue = -0.32f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(1_350, easing = EaseInOutCubic),
                ),
                label = "launchProgressPhase",
            )
            value
        }
    } else {
        progress.coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(1.dp))
            .background(PluviaTheme.colors.borderDefault.copy(alpha = 0.55f)),
    ) {
        if (isIndeterminate) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.32f)
                    .align(Alignment.CenterStart)
                    .graphicsLayer { translationX = phase * size.width / 0.32f }
                    .background(MaterialTheme.colorScheme.primary),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(phase)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Preview(device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape")
@Composable
private fun BootingSplashLandscapePreview() {
    PluviaTheme {
        BootingSplash(
            state = MainState(showBootingSplash = true, bootingSplashText = "Preparing game environment"),
            onShowGameScreen = {},
        )
    }
}
