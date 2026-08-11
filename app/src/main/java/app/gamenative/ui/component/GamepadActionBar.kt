package app.gamenative.ui.component

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.icons.InputIcons
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.motionSpec
import app.gamenative.ui.util.ControllerFamily
import app.gamenative.ui.util.rememberControllerFamily
import app.gamenative.ui.util.shouldShowGamepadUI

// Icons from https://kenney.nl/assets/input-prompts (CC0 License)
enum class GamepadButton(@field:DrawableRes val iconRes: Int) {
    A(InputIcons.Xbox.buttonColorA),
    B(InputIcons.Xbox.buttonColorB),
    X(InputIcons.Xbox.buttonColorX),
    Y(InputIcons.Xbox.buttonColorY),
    LB(InputIcons.Xbox.lb),
    RB(InputIcons.Xbox.rb),
    LT(InputIcons.Xbox.lt),
    RT(InputIcons.Xbox.rt),
    START(InputIcons.Xbox.start),
    SELECT(InputIcons.Xbox.select),
    DPAD(InputIcons.Xbox.dpad),
    DPAD_UP(InputIcons.Xbox.dpadUp),
    DPAD_DOWN(InputIcons.Xbox.dpadDown),
    DPAD_LEFT(InputIcons.Xbox.dpadLeft),
    DPAD_RIGHT(InputIcons.Xbox.dpadRight),
}

/** Resolves the glyph for the given controller family; defaults to the Xbox asset. */
@DrawableRes
fun GamepadButton.iconFor(family: ControllerFamily): Int = when (family) {
    ControllerFamily.XBOX -> iconRes
    ControllerFamily.PLAYSTATION -> when (this) {
        GamepadButton.A -> InputIcons.PlayStation.cross
        GamepadButton.B -> InputIcons.PlayStation.circle
        GamepadButton.X -> InputIcons.PlayStation.square
        GamepadButton.Y -> InputIcons.PlayStation.triangle
        GamepadButton.LB -> InputIcons.PlayStation.l1
        GamepadButton.RB -> InputIcons.PlayStation.r1
        GamepadButton.LT -> InputIcons.PlayStation.l2
        GamepadButton.RT -> InputIcons.PlayStation.r2
        GamepadButton.START -> InputIcons.PlayStation.options
        GamepadButton.SELECT -> InputIcons.PlayStation.share
        GamepadButton.DPAD -> InputIcons.PlayStation.dpad
        GamepadButton.DPAD_UP -> InputIcons.PlayStation.dpadUp
        GamepadButton.DPAD_DOWN -> InputIcons.PlayStation.dpadDown
        GamepadButton.DPAD_LEFT -> InputIcons.PlayStation.dpadLeft
        GamepadButton.DPAD_RIGHT -> InputIcons.PlayStation.dpadRight
    }
}

/** Localized TalkBack label for the glyph actually rendered for this family. */
@StringRes
internal fun GamepadButton.accessibilityLabelFor(family: ControllerFamily): Int = when (family) {
    ControllerFamily.XBOX -> when (this) {
        GamepadButton.A -> R.string.button_a
        GamepadButton.B -> R.string.button_b
        GamepadButton.X -> R.string.button_x
        GamepadButton.Y -> R.string.button_y
        GamepadButton.LB -> R.string.button_l1
        GamepadButton.RB -> R.string.button_r1
        GamepadButton.LT -> R.string.button_l2
        GamepadButton.RT -> R.string.button_r2
        GamepadButton.START -> R.string.button_start
        GamepadButton.SELECT -> R.string.button_select
        GamepadButton.DPAD -> R.string.gamepad_dpad
        GamepadButton.DPAD_UP -> R.string.dpad_up
        GamepadButton.DPAD_DOWN -> R.string.dpad_down
        GamepadButton.DPAD_LEFT -> R.string.dpad_left
        GamepadButton.DPAD_RIGHT -> R.string.dpad_right
    }
    ControllerFamily.PLAYSTATION -> when (this) {
        GamepadButton.A -> R.string.gamepad_ps_cross
        GamepadButton.B -> R.string.gamepad_ps_circle
        GamepadButton.X -> R.string.gamepad_ps_square
        GamepadButton.Y -> R.string.gamepad_ps_triangle
        GamepadButton.LB -> R.string.gamepad_ps_l1
        GamepadButton.RB -> R.string.gamepad_ps_r1
        GamepadButton.LT -> R.string.gamepad_ps_l2
        GamepadButton.RT -> R.string.gamepad_ps_r2
        GamepadButton.START -> R.string.gamepad_ps_options
        GamepadButton.SELECT -> R.string.gamepad_ps_share
        GamepadButton.DPAD -> R.string.gamepad_dpad
        GamepadButton.DPAD_UP -> R.string.dpad_up
        GamepadButton.DPAD_DOWN -> R.string.dpad_down
        GamepadButton.DPAD_LEFT -> R.string.dpad_left
        GamepadButton.DPAD_RIGHT -> R.string.dpad_right
    }
}

data class GamepadAction(
    val button: GamepadButton,
    @get:StringRes val labelResId: Int,
    val onClick: (() -> Unit)? = null,
)

/** A hint made of one or more button glyphs (e.g. "LB/RB") plus a label. */
data class GamepadHint(
    val buttons: List<GamepadButton>,
    @get:StringRes val labelResId: Int,
) {
    constructor(button: GamepadButton, labelResId: Int) : this(listOf(button), labelResId)
}

/**
 * Inline hint row (glyphs + labels) for rails and dialogs. Full-width bottom
 * strips use [GamepadActionBar]; this is the same vocabulary in compact form.
 */
@Composable
fun GamepadHintRow(
    hints: List<GamepadHint>,
    modifier: Modifier = Modifier,
) {
    val swapFaceButtons = PrefManager.swapFaceButtons
    val controllerFamily = rememberControllerFamily()

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hints.forEach { hint ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                hint.buttons.forEach { button ->
                    val effectiveButton = if (swapFaceButtons) {
                        when (button) {
                            GamepadButton.A -> GamepadButton.B
                            GamepadButton.B -> GamepadButton.A
                            GamepadButton.X -> GamepadButton.Y
                            GamepadButton.Y -> GamepadButton.X
                            else -> button
                        }
                    } else {
                        button
                    }
                    Image(
                        painter = painterResource(effectiveButton.iconFor(controllerFamily)),
                        contentDescription = stringResource(effectiveButton.accessibilityLabelFor(controllerFamily)),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = stringResource(hint.labelResId),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun GamepadButtonHint(
    action: GamepadAction,
    swapFaceButtons: Boolean,
    controllerFamily: ControllerFamily,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val clickableModifier = if (action.onClick != null) {
        modifier.clickable(onClick = action.onClick)
    } else {
        modifier
    }

    val label = stringResource(action.labelResId)
    val effectiveButton = if (swapFaceButtons) {
        when (action.button) {
            GamepadButton.A -> GamepadButton.B
            GamepadButton.B -> GamepadButton.A
            GamepadButton.X -> GamepadButton.Y
            GamepadButton.Y -> GamepadButton.X
            else -> action.button
        }
    } else {
        action.button
    }
    val iconRes = effectiveButton.iconFor(controllerFamily)
    val buttonLabel = stringResource(effectiveButton.accessibilityLabelFor(controllerFamily))

    Row(
        modifier = clickableModifier.padding(
            horizontal = if (compact) 3.dp else 6.dp,
            vertical = if (compact) 4.dp else 5.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = buttonLabel,
            modifier = Modifier.size(if (compact) 20.dp else 22.dp),
        )

        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GamepadActionBar(
    actions: List<GamepadAction>,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    forceVisible: Boolean = false,
    compact: Boolean = false,
) {
    val showGamepadUI = shouldShowGamepadUI()
    val swapFaceButtons = PrefManager.swapFaceButtons
    val controllerFamily = rememberControllerFamily()

    AnimatedVisibility(
        visible = visible && actions.isNotEmpty() && (forceVisible || showGamepadUI),
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = motionSpec(tween(durationMillis = 180)),
        ) + fadeIn(animationSpec = motionSpec(tween(durationMillis = 140))),
        exit = slideOutVertically(
            targetOffsetY = { it / 2 },
            animationSpec = motionSpec(tween(durationMillis = 150)),
        ) + fadeOut(animationSpec = motionSpec(tween(durationMillis = 120))),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { canFocus = false }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        ),
                    ),
                ),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = if (compact) 12.dp else 24.dp,
                        vertical = if (compact) 7.dp else 9.dp,
                    ),
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) 8.dp else 16.dp,
                    Alignment.End,
                ),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
            ) {
                actions.forEach { action ->
                    GamepadButtonHint(
                        action = action,
                        swapFaceButtons = swapFaceButtons,
                        controllerFamily = controllerFamily,
                        compact = compact,
                    )
                }
            }
        }
    }
}

object LibraryActions {
    val select = GamepadAction(GamepadButton.A, R.string.action_select)
    val back = GamepadAction(GamepadButton.B, R.string.back)
    val options = GamepadAction(GamepadButton.START, R.string.options)
    val search = GamepadAction(GamepadButton.Y, R.string.search)
    val addGame = GamepadAction(GamepadButton.X, R.string.action_add_game)
    val refresh = GamepadAction(GamepadButton.RB, R.string.action_refresh)
    val play = GamepadAction(GamepadButton.A, R.string.run_app)
    val details = GamepadAction(GamepadButton.X, R.string.action_details)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_GamepadActionBar() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
            ) {
                GamepadActionBar(
                    actions = listOf(
                        LibraryActions.select,
                        LibraryActions.options,
                        LibraryActions.search,
                        LibraryActions.addGame,
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
