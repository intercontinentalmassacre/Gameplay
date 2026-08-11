package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.motionSpec
import kotlinx.coroutines.delay

/**
 * Collapsible settings search: a magnifier button that expands into a search
 * field. Opening moves focus into the field; Back/B/Circle/Escape closes and
 * restores focus to the button. Honors the reduced-motion setting via
 * [motionSpec]. Touch target is 44dp minimum.
 */
@Composable
fun SettingsSearchToggle(
    active: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    fieldWidth: Dp = 220.dp,
) {
    val fieldFocusRequester = remember { FocusRequester() }
    val buttonFocusRequester = remember { FocusRequester() }
    var wasActive by remember { mutableStateOf(false) }

    LaunchedEffect(active) {
        val target = when {
            active -> fieldFocusRequester
            wasActive -> buttonFocusRequester
            else -> null
        }
        if (target != null) {
            for (attempt in 0 until 3) {
                try {
                    target.requestFocus()
                    break
                } catch (_: Exception) {
                    delay(80)
                }
            }
        }
        wasActive = active
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = !active,
            enter = fadeIn(animationSpec = motionSpec(tween(150))),
            exit = fadeOut(animationSpec = motionSpec(tween(120))),
        ) {
            ConsoleIconButton(
                onClick = onOpen,
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.settings_search),
                focusRequester = buttonFocusRequester,
            )
        }

        AnimatedVisibility(
            visible = active,
            enter = fadeIn(animationSpec = motionSpec(tween(150))) +
                expandHorizontally(animationSpec = motionSpec(tween(150))),
            exit = fadeOut(animationSpec = motionSpec(tween(120))) +
                shrinkHorizontally(animationSpec = motionSpec(tween(120))),
        ) {
            NoExtractOutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.settings_search)) },
                singleLine = true,
                modifier = Modifier
                    .width(fieldWidth)
                    .focusRequester(fieldFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Back, Key.Escape, Key.ButtonB -> {
                                onClose()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
    }
}
