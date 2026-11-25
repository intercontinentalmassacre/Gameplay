package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.gcds.GcdsCard
import app.gamenative.ui.gcds.GcdsProgressBar
import app.gamenative.ui.gcds.GcdsProgressBarIndeterminate
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.rememberHasExternalDisplay
import java.text.NumberFormat

/**
 * @param progress A value between 0 and 1 (inclusive), if the value is below 0 then the bar is
 * displayed as indeterminate
 */
@Composable
fun LoadingDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit = {},
    progress: Float,
    message: String = "Loading...",
    embedded: Boolean = false,
) {
    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (visible && !embedded && hasExternalDisplay && !alreadyOnSecondScreen) {
        val secondScreenContent: @Composable () -> Unit = {
            LoadingDialog(
                visible = true,
                onDismissRequest = onDismissRequest,
                progress = progress,
                message = message,
                embedded = true,
            )
        }
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = onDismissRequest,
                    settingsContent = secondScreenContent,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    val hostActive = rememberHostActivityActive()
    if (!visible || !hostActive) return

    val compact = PluviaTheme.tokens.densityCompact
    val fraction = progress.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(1f)
    val locale = LocalConfiguration.current.locales[0]
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = secondScreenDialogProperties(),
    ) {
        GcdsCard(
            modifier = Modifier
                .widthIn(max = PluviaTheme.tokens.panelMaxWidth)
                .fillMaxWidth(),
            containerColor = PluviaTheme.colors.surfaceOverlay,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 16.dp else 24.dp,
                    vertical = if (compact) 12.dp else 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (fraction != null) {
                        Text(
                            text = percentFormat.format(fraction.toDouble()),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (fraction == null) {
                    GcdsProgressBarIndeterminate()
                } else {
                    GcdsProgressBar(progress = fraction)
                }
            }
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LoadingDialog() {
    PluviaTheme {
        LoadingDialog(
            visible = true,
            progress = .75f,
        )
    }
}
