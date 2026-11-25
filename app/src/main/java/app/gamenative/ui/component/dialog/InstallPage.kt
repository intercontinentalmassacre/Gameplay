package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.Button
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.screen.library.components.ConsolePanelHeader
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun InstallPage(
    displayInfo: GameDisplayInfo,
    scrollState: ScrollState,
    sizeSummary: String,
    installEnabled: Boolean,
    onInstall: () -> Unit,
    onBack: () -> Unit,
    showCancel: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val compactDensity = PluviaTheme.tokens.densityCompact
    val locale = Locale.getDefault()
    val metadata = remember(displayInfo.developer, displayInfo.releaseDate, locale) {
        val year = displayInfo.releaseDate.takeIf { it > 0 }?.let {
            SimpleDateFormat("yyyy", locale).format(Date(it * 1000))
        }
        listOfNotNull(displayInfo.developer.takeIf { it.isNotBlank() }, year).joinToString(" · ")
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val shortViewport = maxHeight < 480.dp || LocalSecondScreenDialogWindowType.current != null
            val verticalPadding = if (compactDensity) 4.dp else 8.dp
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    ConsolePanelHeader(
                        title = displayInfo.name,
                        onBack = onBack,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = verticalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(if (compactDensity) 4.dp else 8.dp),
                    ) {
                        displayInfo.heroImageUrl?.takeIf { !shortViewport && it.isNotBlank() }?.let { artwork ->
                            CoilImage(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(144.dp)
                                    .clip(shape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                                imageModel = { artwork },
                                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                                failure = {},
                            )
                        }
                        if (metadata.isNotEmpty()) {
                            Text(
                                text = metadata,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        content()
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = verticalPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = sizeSummary,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Button(
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    enabled = installEnabled,
                                    onClick = onInstall,
                                ) {
                                    Text(stringResource(R.string.install))
                                }
                                if (showCancel) {
                                    TextButton(
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = onBack,
                                    ) { Text(stringResource(R.string.cancel)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstallContentRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val compactDensity = PluviaTheme.tokens.densityCompact

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (compactDensity) 48.dp else 56.dp)
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceContainerHighest
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .focusRing(interactionSource, shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = { onToggle() },
            )
            .padding(horizontal = 16.dp, vertical = if (compactDensity) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
