package app.gamenative.ui.gcds

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

/** Determinate progress bar on GCDS tokens (corner, 6dp height, themed track). */
@Composable
fun GcdsProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val height = if (PluviaTheme.tokens.densityCompact) 6.dp else 8.dp
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(PluviaTheme.tokens.cornerSm)),
        color = color,
        trackColor = trackColor,
    )
}

/** Indeterminate variant for unknown totals. */
@Composable
fun GcdsProgressBarIndeterminate(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val height = if (PluviaTheme.tokens.densityCompact) 6.dp else 8.dp
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(PluviaTheme.tokens.cornerSm)),
        color = color,
        trackColor = trackColor,
    )
}
