package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun GameIdentityArtwork(
    name: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
) {
    Box(modifier) {
        GameIdentityFallback(name, Modifier.fillMaxSize())
        if (!imageUrl.isNullOrBlank()) {
            CoilImage(
                modifier = Modifier.fillMaxSize(),
                imageModel = { imageUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                failure = {
                    if (!fallbackUrl.isNullOrBlank() && fallbackUrl != imageUrl) {
                        CoilImage(
                            modifier = Modifier.fillMaxSize(),
                            imageModel = { fallbackUrl },
                            imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                        )
                    }
                },
            )
        }
    }
}

@Composable
internal fun GameIdentityFallback(name: String, modifier: Modifier = Modifier) {
    val hue = remember(name) { ((name.hashCode() and Int.MAX_VALUE) % 360).toFloat() }
    val ground = Color.hsl(hue, 0.20f, 0.14f)
    val face = Color.hsl(hue, 0.34f, 0.28f)
    val edge = Color.hsl(hue, 0.43f, 0.48f)
    Canvas(modifier) {
        val width = size.width
        val height = size.height
        drawRect(ground)
        drawPath(
            path = Path().apply {
                moveTo(width * 0.55f, 0f)
                lineTo(width, 0f)
                lineTo(width, height)
                lineTo(width * 0.22f, height)
                close()
            },
            color = face,
        )
        drawLine(edge, Offset(width * 0.55f, 0f), Offset(width * 0.22f, height), strokeWidth = 1.5f)
        drawPath(
            path = Path().apply {
                moveTo(width * 0.85f, 0f)
                lineTo(width, 0f)
                lineTo(width, height * 0.64f)
                lineTo(width * 0.66f, height * 0.64f)
                close()
            },
            color = edge.copy(alpha = 0.24f),
        )
    }
}

@Composable
internal fun GameIdentityScrim(modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.28f to Color.Transparent,
                0.60f to Color.Black.copy(alpha = 0.56f),
                1f to Color.Black.copy(alpha = 0.91f),
            ),
        ),
    )
}

@Composable
internal fun GameIdentityTitle(
    name: String,
    logoUrl: String?,
    style: TextStyle,
    logoWidth: Dp,
    logoHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val textTitle: @Composable () -> Unit = {
        Text(
            text = name,
            style = style.copy(
                fontWeight = FontWeight.Bold,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 8f),
            ),
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(modifier) {
        if (logoUrl.isNullOrBlank()) {
            textTitle()
        } else {
            CoilImage(
                imageModel = { logoUrl },
                imageOptions = ImageOptions(
                    alignment = Alignment.BottomStart,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                ),
                modifier = Modifier.width(logoWidth).height(logoHeight),
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) { textTitle() } },
                failure = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) { textTitle() } },
            )
        }
    }
}
