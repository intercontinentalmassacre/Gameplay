package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.theme.isReduceMotionEnabled

/**
 * Skeleton loader for game items that matches the actual game item appearance
 */
@Composable
fun GameSkeletonLoader(
    modifier: Modifier = Modifier,
    paneType: PaneType = PaneType.GRID_CAPSULE,
) {
    val alpha = if (isReduceMotionEnabled()) {
        0.2f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        animatedAlpha
    }

    val skeletonColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (paneType) {
                PaneType.GRID_HERO -> {
                    // Hero view: horizontal image (460:215 aspect ratio)
                    Box(
                        modifier = Modifier
                            .aspectRatio(460f / 215f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .background(skeletonColor)
                    )
                }
                else -> {
                    // Capsule view: vertical image (2:3 aspect ratio).
                    // Legacy PaneType.LIST migrates here as well.
                    Box(
                        modifier = Modifier
                            .aspectRatio(2f / 3f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(3.dp))
                            .background(skeletonColor)
                    )
                }
            }
        }
    }
}
