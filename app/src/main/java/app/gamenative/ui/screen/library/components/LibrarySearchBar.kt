package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.launch

@Composable
fun LibrarySearchBar(
    isVisible: Boolean,
    searchQuery: String,
    resultCount: Int,
    onScrollToTop: suspend () -> Unit,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToResults: () -> Boolean = { false },
) {
    val configuration = LocalConfiguration.current
    val focusManager = LocalFocusManager.current
    var editText by remember { mutableStateOf<EditText?>(null) }
    // A reversed exit keeps the AndroidView alive. Observe open/close outside the transition.
    LaunchedEffect(isVisible, editText) {
        val field = editText
        if (isVisible && field != null) {
            withFrameNanos { }
            if (!field.hasFocus()) {
                focusManager.clearFocus(force = true)
                field.requestFocus()
            }
        }
    }
    val compact = PluviaTheme.tokens.densityCompact ||
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val inlineCount = compact && configuration.screenWidthDp >= 500
    val resultLabel = if (searchQuery.isNotEmpty()) {
        stringResource(
            if (resultCount == 1) R.string.search_results_one else R.string.search_results_many,
            resultCount,
        )
    } else {
        null
    }
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(
            animationSpec = tween(durationMillis = 180),
            expandFrom = Alignment.Top,
        ) + fadeIn(),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = 150),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 10.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchBarInput(
                    onEditTextChanged = { editText = it },
                    searchQuery = searchQuery,
                    onScrollToTop = onScrollToTop,
                    onSearchQuery = onSearchQuery,
                    onDismiss = onDismiss,
                    compact = compact,
                    resultLabel = if (inlineCount) resultLabel else null,
                    onNavigateToResults = onNavigateToResults,
                )

                // Results count
                if (!inlineCount && resultLabel != null) {
                    Text(
                        text = resultLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBarInput(
    onEditTextChanged: (EditText?) -> Unit,
    searchQuery: String,
    onScrollToTop: suspend () -> Unit,
    onSearchQuery: (String) -> Unit,
    onDismiss: () -> Unit,
    compact: Boolean,
    resultLabel: String?,
    onNavigateToResults: () -> Boolean,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val currentOnSearchQuery = rememberUpdatedState(onSearchQuery)
    val currentOnScrollToTop = rememberUpdatedState(onScrollToTop)
    val currentOnNavigateToResults = rememberUpdatedState(onNavigateToResults)
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var isFocused by remember { mutableStateOf(false) }

    val onSearchText: (String) -> Unit = { newText ->
        currentOnSearchQuery.value(newText)
        scope.launch {
            currentOnScrollToTop.value()
        }
    }

    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    val navigateDown = {
        keyboardController?.hide()
        currentOnNavigateToResults.value() || focusManager.moveFocus(FocusDirection.Down)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent {
                val event = it.nativeKeyEvent
                event.action == KeyEvent.ACTION_DOWN &&
                    event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    navigateDown()
            }
            .focusRing(isFocused, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = if (compact) 6.dp else 16.dp, vertical = if (compact) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        ConsoleIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.library_search_close),
            onClick = {
                keyboardController?.hide()
                onDismiss()
            },
        )

        // Search icon
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp),
        )

        // Text input using AndroidView with EditText
        // This allows setting IME_FLAG_NO_EXTRACT_UI to prevent fullscreen keyboard in landscape
        // TODO: there must be a better way of doing this
        val textColor = MaterialTheme.colorScheme.onSurface
        val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
        val placeholderText = stringResource(R.string.library_search_placeholder)

        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    // Prevent fullscreen keyboard (extract mode) in landscape
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_SEARCH
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    isSingleLine = true
                    gravity = Gravity.CENTER_VERTICAL
                    hint = placeholderText
                    background = ColorDrawable(android.graphics.Color.TRANSPARENT)
                    setPadding(0, 0, 0, 0)

                    // Set colors
                    setTextColor(textColor.toArgb())
                    setHintTextColor(hintColor.toArgb())

                    // Text size matching bodyLarge
                    textSize = 16f

                    // Handle search action
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                            keyboardController?.hide()
                            currentOnNavigateToResults.value()
                            true
                        } else {
                            false
                        }
                    }

                    // Handle D-pad navigation
                    setOnKeyListener { v, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            navigateDown() || (v.focusSearch(View.FOCUS_DOWN)?.requestFocus() == true)
                        } else {
                            false
                        }
                    }

                    // Text change listener
                    doAfterTextChanged { editable ->
                        onSearchText(editable?.toString() ?: "")
                    }

                    // Focus change listener for border highlight
                    setOnFocusChangeListener { _, hasFocus ->
                        isFocused = hasFocus
                    }

                    // Store reference for focus management
                    editTextRef = this
                    onEditTextChanged(this)
                }
            },
            onRelease = { onEditTextChanged(null) },
            update = { editText ->
                // Only update if text differs
                if (editText.text.toString() != searchQuery) {
                    editText.setText(searchQuery)
                    editText.setSelection(searchQuery.length)
                }

                // Update colors in case theme changes
                editText.setTextColor(textColor.toArgb())
                editText.setHintTextColor(hintColor.toArgb())
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
        )

        if (resultLabel != null) {
            Text(
                text = resultLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 128.dp),
            )
        }

        if (searchQuery.isNotEmpty()) {
            ConsoleIconButton(
                icon = Icons.Default.Clear,
                contentDescription = stringResource(R.string.library_search_clear),
                onClick = {
                    editTextRef?.requestFocus()
                    onSearchText("")
                },
            )
        }
    }
}

/***********
 * PREVIEW *
 ***********/

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LibrarySearchBar() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface {
            LibrarySearchBar(
                isVisible = true,
                searchQuery = "Balatro",
                resultCount = 5,
                onScrollToTop = { },
                onSearchQuery = { },
                onDismiss = { },
            )
        }
    }
}

@Preview(uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_LibrarySearchBar_Empty() {
    val context = LocalContext.current
    PrefManager.init(context)
    PluviaTheme {
        Surface {
            LibrarySearchBar(
                isVisible = true,
                searchQuery = "",
                resultCount = 0,
                onScrollToTop = { },
                onSearchQuery = { },
                onDismiss = { },
            )
        }
    }
}
