package app.gamenative.ui.screen.library.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.localgames.InstallationState
import app.gamenative.ui.data.InstallProgress
import app.gamenative.ui.theme.PluviaTheme

/**
 * Minimal install progress indicator for library cards. A store download renders a thin
 * determinate bar plus a percentage; a local installer or post-install sync renders an
 * indeterminate sweep plus the current stage / "Finishing" label.
 *
 * Tinted with [PluviaTheme.colors.statusDownloading] — the single accent reserved for the
 * "in progress" state — and kept quiet so it never fights the card art.
 */
@Composable
internal fun InstallProgressOverlay(
    progress: InstallProgress,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp),
    ) {
        when (progress) {
            is InstallProgress.Downloading -> {
                val fraction = progress.fraction.coerceIn(0f, 1f)
                ProgressTrack(
                    determinate = true,
                    fraction = fraction,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
            }

            is InstallProgress.Syncing -> {
                ProgressTrack(
                    determinate = false,
                    fraction = 0f,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    Text(
                        text = "Finishing",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
            }

            is InstallProgress.Installing -> {
                ProgressTrack(
                    determinate = false,
                    fraction = 0f,
                    modifier = Modifier.weight(1f),
                )
                if (!compact) {
                    Text(
                        text = stageLabel(progress.stage),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressTrack(
    determinate: Boolean,
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val trackColor = Color.Black.copy(alpha = 0.35f)
    val barColor = PluviaTheme.colors.statusDownloading

    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(trackColor),
    ) {
        if (determinate) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .matchParentSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
        } else {
            val transition = rememberInfiniteTransition(label = "installIndeterminate")
            val sweepX by transition.animateFloat(
                initialValue = -0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "installSweepX",
            )
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .matchParentSize()
                    .graphicsLayer { translationX = sweepX * 100f }
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
        }
    }
}

private fun stageLabel(state: InstallationState): String = when (state) {
    InstallationState.SOURCE_STAGED -> "Preparing"
    InstallationState.CONTAINER_CREATING -> "Setting up"
    InstallationState.READY_TO_LAUNCH -> "Ready to install"
    InstallationState.INSTALLER_RUNNING -> "Installing…"
    InstallationState.AWAITING_RESULT -> "Finalizing"
    InstallationState.CANDIDATE_SELECTION -> "Confirming"
    InstallationState.RESTART_REQUIRED -> "Restart needed"
    InstallationState.PAUSED -> "Paused"
    InstallationState.COMPLETED, InstallationState.FAILED, InstallationState.CANCELLED -> "Installing"
}