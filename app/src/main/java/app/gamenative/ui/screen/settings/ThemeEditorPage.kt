package app.gamenative.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Restore
import app.gamenative.ui.component.Button
import app.gamenative.ui.component.FilterChip
import androidx.compose.material3.Icon
import app.gamenative.ui.component.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.ConsoleCategoryRail
import app.gamenative.ui.gcds.GcdsRail
import app.gamenative.ui.component.GamepadHint
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.dialog.ConsoleSettingsPage
import app.gamenative.ui.theme.GameplayThemeCodec
import app.gamenative.ui.theme.GameplayThemeDecodeResult
import app.gamenative.ui.theme.GameplayThemeDocument
import app.gamenative.ui.theme.GameplayThemePalette
import app.gamenative.ui.theme.GameplayThemeTokens

private enum class ThemeEditorSection(val labelRes: Int) {
    GENERAL(R.string.theme_editor_section_general),
    LAYOUT(R.string.theme_editor_section_layout),
    DARK(R.string.theme_editor_section_dark),
    LIGHT(R.string.theme_editor_section_light),
    PREVIEW(R.string.theme_editor_section_preview),
}

@Composable
fun ThemeEditorPage(
    visible: Boolean,
    initialDocument: GameplayThemeDocument,
    onSave: (GameplayThemeDocument) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var draft by remember(initialDocument) { mutableStateOf(initialDocument) }
    var selectedSection by remember { mutableStateOf(ThemeEditorSection.GENERAL) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val themeWarnings = remember(draft) { GameplayThemeCodec.warnings(draft) }

    fun save() {
        when (val result = GameplayThemeCodec.decode(GameplayThemeCodec.encode(draft))) {
            is GameplayThemeDecodeResult.Success -> onSave(result.document)
            is GameplayThemeDecodeResult.Error -> validationError = result.reason
        }
    }

    ConsoleSettingsPage(
        visible = true,
        title = stringResource(R.string.theme_editor_title),
        onDismissRequest = onDismiss,
        actions = {
            IconButton(
                onClick = {
                    draft = GameplayThemeCodec.safeDocument(name = draft.name.ifBlank { "Gameplay Slate" })
                    validationError = null
                },
            ) {
                Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.theme_editor_reset_draft))
            }
            IconButton(onClick = ::save) {
                Icon(Icons.Default.Save, contentDescription = stringResource(R.string.theme_editor_save))
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            GcdsRail(
                items = ThemeEditorSection.entries,
                selectedItem = selectedSection,
                label = { stringResource(it.labelRes) },
                onSelected = {
                    selectedSection = it
                    validationError = null
                },
                footer = stringResource(R.string.container_config_console_controls_hint),
                    footerHints = listOf(
                        GamepadHint(listOf(GamepadButton.LB, GamepadButton.RB), R.string.hint_categories),
                        GamepadHint(GamepadButton.A, R.string.action_select),
                        GamepadHint(GamepadButton.B, R.string.back),
                    ),
                requestInitialFocus = true,
                compact = true,
                modifier = Modifier.width(190.dp),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 12.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                validationError?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                if (validationError == null && themeWarnings.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.theme_editor_warning_title),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            themeWarnings.forEach { warning ->
                                Text(
                                    text = stringResource(
                                        R.string.theme_editor_acidic_warning,
                                        warning.tokenPath,
                                    ),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }

                when (selectedSection) {
                    ThemeEditorSection.GENERAL -> GeneralThemeEditor(
                        name = draft.name,
                        onNameChange = { draft = draft.copy(name = it.take(48)) },
                    )
                    ThemeEditorSection.LAYOUT -> ThemeLayoutEditor(
                        tokens = draft.tokens,
                        onChange = { draft = draft.copy(tokens = it) },
                    )
                    ThemeEditorSection.DARK -> PaletteEditor(
                        palette = draft.dark,
                        onChange = { draft = draft.copy(dark = it) },
                    )
                    ThemeEditorSection.LIGHT -> PaletteEditor(
                        palette = draft.light,
                        onChange = { draft = draft.copy(light = it) },
                    )
                    ThemeEditorSection.PREVIEW -> ThemePreview(document = draft)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = ::save, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.theme_editor_save_and_apply))
                }
            }
        }
    }
}

@Composable
private fun GeneralThemeEditor(name: String, onNameChange: (String) -> Unit) {
    Text(
        text = stringResource(R.string.theme_editor_general_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NoExtractOutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.theme_editor_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ThemeLayoutEditor(
    tokens: GameplayThemeTokens,
    onChange: (GameplayThemeTokens) -> Unit,
) {
    Text(
        text = stringResource(R.string.theme_editor_layout_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ThemeTokenSection(stringResource(R.string.theme_editor_layout_panels)) {
        ThemeSlider(stringResource(R.string.theme_editor_token_panel_width), tokens.panelMaxWidthDp, 320..640, "dp") {
            onChange(tokens.copy(panelMaxWidthDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_panel_horizontal_padding), tokens.panelHorizontalPaddingDp, 12..48, "dp") {
            onChange(tokens.copy(panelHorizontalPaddingDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_panel_vertical_padding), tokens.panelVerticalPaddingDp, 8..40, "dp") {
            onChange(tokens.copy(panelVerticalPaddingDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_menu_gap), tokens.menuItemGapDp, 2..24, "dp") {
            onChange(tokens.copy(menuItemGapDp = it))
        }
    }

    ThemeTokenSection(stringResource(R.string.theme_editor_layout_shape)) {
        ThemeSlider(stringResource(R.string.theme_editor_token_corner_small), tokens.cornerSmDp, 2..24, "dp") {
            onChange(tokens.copy(cornerSmDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_corner_medium), tokens.cornerMdDp, 4..32, "dp") {
            onChange(tokens.copy(cornerMdDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_corner_large), tokens.cornerLgDp, 6..40, "dp") {
            onChange(tokens.copy(cornerLgDp = it))
        }
        ThemeSlider(stringResource(R.string.theme_editor_token_focus_width), tokens.focusRingWidthDp, 1..8, "dp") {
            onChange(tokens.copy(focusRingWidthDp = it))
        }

        Text(
            text = stringResource(R.string.theme_editor_token_focus_style),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tokens.focusRingStyle == "solid",
                onClick = { onChange(tokens.copy(focusRingStyle = "solid")) },
                label = { Text(stringResource(R.string.theme_editor_focus_solid)) },
            )
            FilterChip(
                selected = tokens.focusRingStyle == "dashed",
                onClick = { onChange(tokens.copy(focusRingStyle = "dashed")) },
                label = { Text(stringResource(R.string.theme_editor_focus_dashed)) },
            )
        }
    }

    ThemeTokenSection(stringResource(R.string.theme_editor_layout_density)) {
        Text(
            text = stringResource(R.string.theme_editor_density_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tokens.density == "compact",
                onClick = { onChange(tokens.copy(density = "compact")) },
                label = { Text(stringResource(R.string.theme_editor_density_compact)) },
            )
            FilterChip(
                selected = tokens.density == "comfortable",
                onClick = { onChange(tokens.copy(density = "comfortable")) },
                label = { Text(stringResource(R.string.theme_editor_density_comfortable)) },
            )
        }
    }

    ThemeTokenSection(stringResource(R.string.theme_editor_accessibility)) {
        Text(
            text = stringResource(R.string.theme_editor_high_contrast_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = tokens.highContrast,
            onClick = { onChange(tokens.copy(highContrast = !tokens.highContrast)) },
            label = { Text(stringResource(R.string.theme_editor_high_contrast)) },
        )
    }
}

@Composable
private fun ThemeTokenSection(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ThemeSlider(
    label: String,
    value: Int,
    range: IntRange,
    suffix: String,
    onChange: (Int) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "$value $suffix",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun PaletteEditor(
    palette: GameplayThemePalette,
    onChange: (GameplayThemePalette) -> Unit,
) {
    Text(
        text = stringResource(R.string.theme_editor_palette_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ColorTokenField(R.string.theme_token_primary, palette.primary) { onChange(palette.copy(primary = it)) }
    ColorTokenField(R.string.theme_token_on_primary, palette.onPrimary) { onChange(palette.copy(onPrimary = it)) }
    ColorTokenField(R.string.theme_token_background, palette.background) { onChange(palette.copy(background = it)) }
    ColorTokenField(R.string.theme_token_on_background, palette.onBackground) { onChange(palette.copy(onBackground = it)) }
    ColorTokenField(R.string.theme_token_surface, palette.surface) { onChange(palette.copy(surface = it)) }
    ColorTokenField(R.string.theme_token_surface_elevated, palette.surfaceElevated) { onChange(palette.copy(surfaceElevated = it)) }
    ColorTokenField(R.string.theme_token_on_surface, palette.onSurface) { onChange(palette.copy(onSurface = it)) }
    ColorTokenField(R.string.theme_token_text_muted, palette.textMuted) { onChange(palette.copy(textMuted = it)) }
    ColorTokenField(R.string.theme_token_border, palette.border) { onChange(palette.copy(border = it)) }
    ColorTokenField(R.string.theme_token_success, palette.success) { onChange(palette.copy(success = it)) }
    ColorTokenField(R.string.theme_token_warning, palette.warning) { onChange(palette.copy(warning = it)) }
    ColorTokenField(R.string.theme_token_danger, palette.danger) { onChange(palette.copy(danger = it)) }
}

@Composable
private fun ColorTokenField(labelRes: Int, value: String, onValueChange: (String) -> Unit) {
    val valid = remember(value) { Regex("^#[0-9A-Fa-f]{6}$").matches(value) }
    val color = remember(value, valid) {
        if (valid) GameplayThemeCodec.color(value) else Color.Transparent
    }
    NoExtractOutlinedTextField(
        value = value,
        onValueChange = { next ->
            val digits = next.uppercase().filter { char -> char in '0'..'9' || char in 'A'..'F' }
            onValueChange("#${digits.take(6)}")
        },
        label = { Text(stringResource(labelRes)) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(color, RoundedCornerShape(5.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)),
            )
        },
        supportingText = if (valid) null else ({ Text(stringResource(R.string.theme_editor_hex_error)) }),
        isError = !valid,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ThemePreview(document: GameplayThemeDocument) {
    Text(
        text = stringResource(R.string.theme_editor_preview_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PalettePreview(
            title = stringResource(R.string.theme_editor_section_dark),
            palette = document.dark,
            tokens = document.tokens,
            modifier = Modifier.weight(1f),
        )
        PalettePreview(
            title = stringResource(R.string.theme_editor_section_light),
            palette = document.light,
            tokens = document.tokens,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PalettePreview(
    title: String,
    palette: GameplayThemePalette,
    tokens: GameplayThemeTokens,
    modifier: Modifier = Modifier,
) {
    fun color(value: String, fallback: Color): Color =
        if (Regex("^#[0-9A-Fa-f]{6}$").matches(value)) GameplayThemeCodec.color(value) else fallback

    val background = color(palette.background, MaterialTheme.colorScheme.background)
    val surface = color(palette.surface, MaterialTheme.colorScheme.surface)
    val elevated = color(palette.surfaceElevated, MaterialTheme.colorScheme.surfaceContainerHigh)
    val contrastContent = if (background.luminance() < 0.5f) Color.White else Color.Black
    val content = if (tokens.highContrast) contrastContent
        else color(palette.onSurface, MaterialTheme.colorScheme.onSurface)
    val muted = if (tokens.highContrast) contrastContent.copy(alpha = 0.84f)
        else color(palette.textMuted, MaterialTheme.colorScheme.onSurfaceVariant)
    val primary = color(palette.primary, MaterialTheme.colorScheme.primary)
    val focus = if (tokens.highContrast) contrastContent
        else palette.focusRingColor?.let { color(it, primary) } ?: primary

    Column(
        modifier = modifier
            .background(background, RoundedCornerShape(tokens.cornerLgDp.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = color(palette.onBackground, content), fontWeight = FontWeight.SemiBold)
        Surface(color = surface, shape = RoundedCornerShape(tokens.cornerMdDp.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(documentPreviewTitle(), color = content, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.theme_editor_preview_secondary), color = muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(30.dp).background(primary, RoundedCornerShape(tokens.cornerSmDp.dp)))
                    Box(Modifier.size(30.dp).background(color(palette.success, primary), RoundedCornerShape(tokens.cornerSmDp.dp)))
                    Box(Modifier.size(30.dp).background(color(palette.warning, primary), RoundedCornerShape(tokens.cornerSmDp.dp)))
                    Box(Modifier.size(30.dp).background(color(palette.danger, primary), RoundedCornerShape(tokens.cornerSmDp.dp)))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(elevated, RoundedCornerShape(tokens.cornerSmDp.dp))
                        .border(
                            tokens.focusRingWidthDp.dp,
                            focus,
                            RoundedCornerShape(tokens.cornerSmDp.dp),
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(R.string.theme_editor_preview_selected),
                        color = content,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun documentPreviewTitle(): String = stringResource(R.string.theme_editor_preview_title)
