package app.gamenative.ui.gcds

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

/** Theme-aware root notification surface. */
@Composable
fun GcdsToast(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(PluviaTheme.tokens.cornerLg),
        color = PluviaTheme.colors.surfaceOverlay,
        shadowElevation = PluviaTheme.tokens.elevationFocus,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
