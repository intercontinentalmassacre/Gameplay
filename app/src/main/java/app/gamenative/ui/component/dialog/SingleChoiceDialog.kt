package app.gamenative.ui.component.dialog

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun SingleChoiceDialog(
    openDialog: Boolean,
    icon: ImageVector? = null,
    iconDescription: String? = null,
    title: String,
    items: List<String>,
    currentItem: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    itemPreview: (@Composable (Int) -> Unit)? = null,
    itemDescription: (@Composable (Int) -> String?)? = null,
) {
    if (!openDialog) {
        return
    }

    ConsoleSettingsPage(
        visible = openDialog,
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = iconDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        },
    ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEachIndexed { index, entry ->
                    val selected = index == currentItem
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .background(
                                color = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                shape = RoundedCornerShape(10.dp),
                            )
                            .focusRing(interactionSource, RoundedCornerShape(10.dp), width = 2.dp)
                            .selectable(
                                selected = selected,
                                onClick = { onSelected(index) },
                                role = Role.RadioButton,
                                interactionSource = interactionSource,
                                indication = null,
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemPreview?.invoke(index)
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            modifier = if (itemPreview != null) Modifier.padding(start = 10.dp) else Modifier,
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(text = entry, style = MaterialTheme.typography.bodyLarge)
                            itemDescription?.invoke(index)?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_SingleChoiceDialog() {
    val content = LocalContext.current
    PrefManager.init(content)

    val list = remember { AppTheme.entries }
    var theme by remember { mutableStateOf(AppTheme.NIGHT) }

    PluviaTheme {
        SingleChoiceDialog(
            openDialog = true,
            items = list.map { it.text },
            icon = Icons.Default.BrightnessMedium,
            title = "App Theme",
            currentItem = theme.ordinal,
            onSelected = { theme = list[it] },
            onDismiss = { },
        )
    }
}
