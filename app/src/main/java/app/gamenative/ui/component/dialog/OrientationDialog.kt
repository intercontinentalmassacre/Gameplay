package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme
import java.util.EnumSet

@Composable
fun OrientationDialog(
    openDialog: Boolean,
    onDismiss: () -> Unit,
) {
    if (!openDialog) {
        return
    }

    var currentSettings by remember {
        mutableStateOf(PrefManager.allowedOrientation.toList())
    }

    // Save on close.
    val onClose: () -> Unit = {
        PrefManager.allowedOrientation = EnumSet.copyOf(currentSettings)
        onDismiss()
    }

    ConsoleSettingsPage(
        visible = openDialog,
        title = stringResource(R.string.allowed_orientations),
        onDismissRequest = {
            // Block dismissal unless there is one valid setting checked.
            if (currentSettings.isNotEmpty()) {
                onClose()
            }
        },
        actions = {
            Icon(
                imageVector = Icons.Default.ScreenRotation,
                contentDescription = stringResource(R.string.allowed_orientations),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                arrayOf(Orientation.LANDSCAPE, Orientation.REVERSE_LANDSCAPE).forEach { orientation ->
                    val checked = currentSettings.contains(orientation)
                    val interactionSource = remember(orientation) { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .background(
                                color = if (checked) {
                                    androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .focusRing(interactionSource, RoundedCornerShape(10.dp), width = 2.dp)
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                interactionSource = interactionSource,
                                indication = null,
                                onValueChange = { enable ->
                                    currentSettings = if (enable) {
                                        currentSettings + orientation
                                    } else {
                                        currentSettings - orientation
                                    }
                                },
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                        )
                        Text(
                            text = orientation.name,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_ProfileDialog() {
    val content = LocalContext.current
    PrefManager.init(content)
    PluviaTheme {
        OrientationDialog(
            openDialog = true,
            onDismiss = {},
        )
    }
}
