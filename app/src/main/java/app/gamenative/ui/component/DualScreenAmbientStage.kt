package app.gamenative.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.LocalReduceMotion
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/**
 * Shared upper-display scene for dual-screen workspaces. The lower display owns
 * controls and dense content; this surface provides context without duplicating
 * those controls or competing for attention.
 */
@Composable
fun DualScreenAmbientStage(
    icon: ImageVector,
    label: String,
    title: String,
    description: String,
    accent: Color,
    modifier: Modifier = Modifier,
    hint: String? = null,
    ambientArtUrls: List<String> = emptyList(),
) {
    val background = MaterialTheme.colorScheme.background
    val onBackground = MaterialTheme.colorScheme.onBackground
    val surface = PluviaTheme.colors.surfacePanel
    val reduceMotion = LocalReduceMotion.current

    // Ambient mode: after 30s idle, slow crossfade of library art behind the scene
    // (any navigation away drops this composable, which is the instant return).
    val artIndex by produceState(initialValue = -1, key1 = ambientArtUrls, key2 = reduceMotion) {
        if (ambientArtUrls.isEmpty() || reduceMotion) return@produceState
        delay(30_000)
        value = 0
        while (true) {
            delay(8_000)
            value = (value + 1) % ambientArtUrls.size
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to lerp(background, accent, 0.16f),
                        0.48f to background,
                        1f to lerp(surface, accent, 0.10f),
                    ),
                ),
            )
            .statusBarsPadding()
            .displayCutoutPadding(),
    ) {
        if (artIndex >= 0) {
            Crossfade(
                targetState = ambientArtUrls.getOrNull(artIndex).orEmpty(),
                animationSpec = tween(1200),
                label = "ambient_art_fade",
                modifier = Modifier.fillMaxSize(),
            ) { url ->
                if (url.isNotEmpty()) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { url },
                        imageOptions = ImageOptions(contentScale = ContentScale.Crop, contentDescription = null),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background.copy(alpha = 0.72f)),
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sceneCenter = Offset(size.width * 0.78f, size.height * 0.50f)
            val baseRadius = size.minDimension * 0.25f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.20f),
                        accent.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    center = sceneCenter,
                    radius = baseRadius * 1.95f,
                ),
                radius = baseRadius * 1.95f,
                center = sceneCenter,
            )
            drawCircle(
                color = accent.copy(alpha = 0.16f),
                radius = baseRadius * 1.38f,
                center = sceneCenter,
                style = Stroke(width = 1.dp.toPx()),
            )
            drawArc(
                color = accent.copy(alpha = 0.34f),
                startAngle = -48f,
                sweepAngle = 124f,
                useCenter = false,
                topLeft = Offset(sceneCenter.x - baseRadius, sceneCenter.y - baseRadius),
                size = Size(baseRadius * 2f, baseRadius * 2f),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            drawCircle(
                color = onBackground.copy(alpha = 0.045f),
                radius = baseRadius * 0.72f,
                center = sceneCenter,
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = onBackground.copy(alpha = 0.78f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp, end = 520.dp)
                .widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(3.dp)
                    .background(accent),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = onBackground,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.titleMedium,
                color = onBackground.copy(alpha = 0.74f),
                modifier = Modifier.widthIn(max = 600.dp),
            )
        }

        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 112.dp)
                .size(152.dp),
        )

        if (!hint.isNullOrBlank()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = onBackground.copy(alpha = 0.52f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 48.dp, vertical = 34.dp),
            )
        }
    }
}
