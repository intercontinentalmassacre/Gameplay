package app.gamenative.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme
import androidx.compose.material3.SegmentedButton as MaterialSegmentedButton

/**
 * Controller-first 44dp icon button shared by settings surfaces and dialogs.
 * Same focus vocabulary as [ConsoleCategoryRail]: elevated surface at rest,
 * primary container + focus ring while focused.
 */
@Composable
fun ConsoleIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(0.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    PluviaTheme.colors.surfaceElevated
                },
            )
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Controller-first list row (search results, pickers). Shares the
 * [ConsoleCategoryRail] focus vocabulary: elevated background + ring on
 * focus, quiet at rest. 44dp+ touch target.
 */
@Composable
fun ConsoleListRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Controller-first text button for dialog action rows. Rest state is quiet;
 * focus is unmistakable (elevated background + ring), primary actions keep a
 * tinted container so the default choice reads before focus moves.
 */
@Composable
fun ConsoleDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    isPrimary: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                when {
                    isFocused -> MaterialTheme.colorScheme.primaryContainer
                    isPrimary -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
            )
            .focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                isFocused -> MaterialTheme.colorScheme.onPrimaryContainer
                isPrimary -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Drop-in replacement for the material3 FilledTonalButton with a visible
 * controller focus state (focus ring). Touch/click behavior unchanged.
 */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val interactionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by interactionSource.collectIsFocusedAsState()

    androidx.compose.material3.FilledTonalButton(
        onClick = onClick,
        modifier = modifier.focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for the material3 OutlinedButton with a visible
 * controller focus state (focus ring). Touch/click behavior unchanged.
 */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val interactionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by interactionSource.collectIsFocusedAsState()

    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = interactionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for the material3 IconButton: 44dp minimum touch
 * target and a visible controller focus state (elevated background + ring).
 */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .focusRing(resolvedInteractionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        colors = colors,
        interactionSource = resolvedInteractionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for the material3 Button with a visible controller
 * focus state (focus ring). Touch/click behavior unchanged.
 */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()

    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.focusRing(resolvedInteractionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = resolvedInteractionSource,
        content = content,
    )
}

/**
 * Drop-in replacement for the material3 FilterChip with a visible controller
 * focus state (focus ring). Touch/click behavior unchanged.
 */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = androidx.compose.material3.FilterChipDefaults.shape,
    colors: androidx.compose.material3.SelectableChipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(),
    elevation: androidx.compose.material3.SelectableChipElevation? = androidx.compose.material3.FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = selected,
    ),
    interactionSource: MutableInteractionSource? = null,
) {
    val fallbackInteractionSource = remember { MutableInteractionSource() }
    val resolvedInteractionSource = interactionSource ?: fallbackInteractionSource
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()

    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier.focusRing(resolvedInteractionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        elevation = elevation,
        border = border,
        interactionSource = resolvedInteractionSource,
    )
}

/**
 * Drop-in replacement for the material3 SegmentedButton with a visible
 * controller focus state (focus ring). Touch/click behavior unchanged.
 */
@Composable
fun androidx.compose.material3.SingleChoiceSegmentedButtonRowScope.SegmentedButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.SegmentedButtonColors = androidx.compose.material3.SegmentedButtonDefaults.colors(),
    border: BorderStroke = androidx.compose.material3.SegmentedButtonDefaults.borderStroke(
        color = MaterialTheme.colorScheme.outline,
    ),
    icon: @Composable () -> Unit = { androidx.compose.material3.SegmentedButtonDefaults.Icon(selected) },
    label: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    MaterialSegmentedButton(
        selected = selected,
        onClick = onClick,
        shape = shape,
        modifier = modifier.focusRing(interactionSource, shape, width = PluviaTheme.tokens.focusRingWidth),
        enabled = enabled,
        colors = colors,
        border = border,
        icon = icon,
        label = label,
        interactionSource = interactionSource,
    )
}
