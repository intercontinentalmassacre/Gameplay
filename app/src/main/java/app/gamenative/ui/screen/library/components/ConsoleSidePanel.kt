package app.gamenative.ui.screen.library.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.externaldisplay.DsHomeSecondScreen
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.dialog.LocalSecondScreenDialogWindowType
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.iconFor
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.ui.util.rememberControllerFamily
import app.gamenative.ui.util.shouldShowGamepadUI
import app.gamenative.utils.rememberHasExternalDisplay

/**
 * Shared console side-panel shell: scrim, slide-from-right motion, surface,
 * Back/B dismiss handling, and focus-on-open behavior. Panels supply only
 * their content inside the padded column.
 */
@Composable
fun ConsoleSidePanel(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester = remember { FocusRequester() },
    focusRequestKey: Any? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrimAlpha: Float = 0.58f,
    surfaceElevation: Dp = 0.dp,
    fullScreen: Boolean = false,
    embedded: Boolean = false,
    content: @Composable ColumnScope.(FocusRequester) -> Unit,
) {
    val hasExternalDisplay = rememberHasExternalDisplay()
    val alreadyOnSecondScreen = LocalSecondScreenDialogWindowType.current != null
    if (isOpen && !embedded && hasExternalDisplay && !alreadyOnSecondScreen) {
        val secondScreenContent: @Composable () -> Unit = {
            ConsoleSidePanel(
                isOpen = true,
                onDismiss = onDismiss,
                modifier = modifier,
                focusRequestKey = focusRequestKey,
                verticalArrangement = verticalArrangement,
                scrimAlpha = 0f,
                surfaceElevation = 0.dp,
                fullScreen = true,
                embedded = true,
                content = content,
            )
        }
        SideEffect {
            DsHomeSecondScreen.publish(
                DsHomeSecondScreen.Model(
                    owner = DsHomeSecondScreen.Owner.DIALOG,
                    mode = DsHomeSecondScreen.Mode.SETTINGS,
                    onBack = onDismiss,
                    settingsContent = secondScreenContent,
                ),
            )
        }
        DisposableEffect(Unit) {
            onDispose { DsHomeSecondScreen.clear(DsHomeSecondScreen.Owner.DIALOG) }
        }
        return
    }

    BackHandler(enabled = isOpen, onBack = onDismiss)

    LaunchedEffect(isOpen, focusRequestKey) {
        if (isOpen) {
            kotlinx.coroutines.delay(80)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs))),
            exit = fadeOut(animationSpec = motionSpec(tween(PluviaTheme.tokens.motionFastMs))),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            modifier = Modifier.align(if (fullScreen) Alignment.Center else Alignment.CenterEnd),
            enter = slideInHorizontally(motionSpec(tween(PluviaTheme.tokens.motionNormalMs))) { it },
            exit = slideOutHorizontally(motionSpec(tween(PluviaTheme.tokens.motionFastMs))) { it },
        ) {
            Surface(
                modifier = if (fullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxHeight()
                        .width(adaptivePanelWidth(PluviaTheme.tokens.panelMaxWidth))
                },
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = surfaceElevation,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .then(
                                if (fullScreen) {
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth()
                                        .widthIn(max = 760.dp)
                                        .align(Alignment.TopCenter)
                                } else {
                                    Modifier.fillMaxSize()
                                },
                            )
                            .padding(
                                horizontal = PluviaTheme.tokens.panelHorizontalPadding,
                                vertical = PluviaTheme.tokens.panelVerticalPadding,
                            ),
                        verticalArrangement = verticalArrangement,
                    ) {
                        content(firstItemFocusRequester)
                    }
                }
            }
        }
    }
}

/** Bottom-pinned controller hint row used by menu-style side panels. */
@Composable
fun ColumnScope.ConsolePanelBackHint() {
    Spacer(modifier = Modifier.weight(1f))
    if (shouldShowGamepadUI()) {
        val controllerFamily = rememberControllerFamily()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(GamepadButton.B.iconFor(controllerFamily)),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.back),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            text = stringResource(R.string.console_back_hint),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shared icon+label action row for menu-style side panels. */
@Composable
fun ConsoleMenuActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(0.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(interactionSource, shape, width = 2.dp)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primaryContainer
                    emphasized -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                },
                shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(23.dp),
            tint = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (focused || emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    }
}
