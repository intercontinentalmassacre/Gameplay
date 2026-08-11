package app.gamenative.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.isReduceMotionEnabled
import app.gamenative.ui.theme.motionSpec
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Full-screen console launch surface shown while a game or container is prepared. */
@Composable
fun BootingSplash(
    visible: Boolean = true,
    text: String = "Initializing...",
    progress: Float = -1f,
    heroImageUrl: String = "",
) {
    val context = LocalContext.current
    val tips = remember(context) {
        listOf(
            context.getString(R.string.game_launch_tip_1),
            context.getString(R.string.game_launch_tip_2, context.getString(R.string.option_open_container)),
            context.getString(R.string.game_launch_tip_3),
            context.getString(R.string.game_launch_tip_4),
            context.getString(R.string.game_launch_tip_5, context.getString(R.string.option_test_graphics)),
            context.getString(R.string.game_launch_tip_6),
            context.getString(R.string.game_launch_tip_7),
            context.getString(R.string.game_launch_tip_8),
            context.getString(R.string.game_launch_tip_9, context.getString(R.string.option_open_container)),
            context.getString(R.string.game_launch_tip_11),
            context.getString(R.string.game_launch_tip_12),
            context.getString(R.string.game_launch_tip_13),
            context.getString(R.string.game_launch_tip_14),
            context.getString(R.string.game_launch_tip_15),
            context.getString(R.string.game_launch_tip_16),
            context.getString(R.string.game_launch_tip_17),
            context.getString(R.string.game_launch_tip_18),
            context.getString(R.string.game_launch_tip_19),
            context.getString(R.string.game_launch_tip_20),
            context.getString(R.string.game_launch_tip_21),
            context.getString(R.string.game_launch_tip_22),
            context.getString(R.string.game_launch_tip_23),
            context.getString(R.string.game_launch_tip_24, context.getString(R.string.option_test_graphics)),
            context.getString(R.string.game_launch_tip_25),
        )
    }
    var tipIndex by remember { mutableStateOf(if (tips.isEmpty()) 0 else Random.nextInt(tips.size)) }

    LaunchedEffect(visible, tips) {
        while (visible && tips.isNotEmpty()) {
            delay(8_000)
            tipIndex = (tipIndex + 1) % tips.size
        }
    }

    AnimatedVisibility(
        visible = visible,
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
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.42f) })
                }
                CoilImage(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(0.62f),
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
                            0f to MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
                            0.58f to MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                            1f to MaterialTheme.colorScheme.background.copy(alpha = 0.46f),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp, vertical = 30.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = 18.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier.widthIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    LaunchProgressBar(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                if (tips.isNotEmpty()) {
                    Crossfade(
                        targetState = tipIndex,
                        animationSpec = motionSpec(tween(260, easing = EaseInOutCubic)),
                        label = "launchTip",
                    ) { index ->
                        Text(
                            text = tips[index],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 760.dp),
                        )
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
        BootingSplash(text = "Preparing game environment")
    }
}
