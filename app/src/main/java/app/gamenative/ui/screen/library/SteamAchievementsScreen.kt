package app.gamenative.ui.screen.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.ConsoleIconButton
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.data.Achievement
import app.gamenative.ui.theme.PluviaTheme
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlin.math.roundToInt

private enum class AchievementFilter {
    ALL,
    UNLOCKED,
    LOCKED,
}

@Composable
internal fun SteamAchievementsPage(
    gameName: String,
    achievements: List<Achievement>,
    onBack: () -> Unit,
    rarity: Map<String, Float> = emptyMap(),
) {
    var filter by rememberSaveable { mutableStateOf(AchievementFilter.ALL) }
    var revealSecrets by rememberSaveable { mutableStateOf(false) }
    var selectedAchievement by remember { mutableStateOf(achievements.firstOrNull { it.isUnlocked } ?: achievements.firstOrNull()) }
    val hiddenLocked = remember(achievements) { achievements.filter { it.hidden && !it.isUnlocked } }
    val visibleAchievements = remember(achievements, filter, revealSecrets) {
        achievements
            .asSequence()
            .filter { revealSecrets || !(it.hidden && !it.isUnlocked) }
            .filter {
                when (filter) {
                    AchievementFilter.ALL -> true
                    AchievementFilter.UNLOCKED -> it.isUnlocked
                    AchievementFilter.LOCKED -> !it.isUnlocked
                }
            }
            .sortedWith(compareByDescending<Achievement> { it.isUnlocked }.thenBy { it.displayName.lowercase() })
            .toList()
    }

    LaunchedEffect(visibleAchievements) {
        if (selectedAchievement !in visibleAchievements) selectedAchievement = visibleAchievements.firstOrNull()
    }
    BackHandler(onBack = onBack)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.ButtonB || event.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(bottom = 52.dp)) {
                AchievementHeader(
                    gameName = gameName,
                    unlocked = achievements.count(Achievement::isUnlocked),
                    total = achievements.size,
                    onBack = onBack,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                AchievementFilters(filter = filter, onFilter = { filter = it })

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (maxWidth >= 720.dp) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            AchievementList(
                                achievements = visibleAchievements,
                                hiddenCount = if (!revealSecrets && filter != AchievementFilter.UNLOCKED) hiddenLocked.size else 0,
                                onRevealSecrets = { revealSecrets = true },
                                selected = selectedAchievement,
                                onSelected = { selectedAchievement = it },
                                rarity = rarity,
                                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            )
                            AchievementDetails(
                                achievement = selectedAchievement,
                                rarity = selectedAchievement?.name?.let { rarity[it] },
                                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                            )
                        }
                    } else {
                        AchievementList(
                            achievements = visibleAchievements,
                            hiddenCount = if (!revealSecrets && filter != AchievementFilter.UNLOCKED) hiddenLocked.size else 0,
                            onRevealSecrets = { revealSecrets = true },
                            selected = selectedAchievement,
                            onSelected = { selectedAchievement = it },
                            rarity = rarity,
                            showDescriptions = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            GamepadActionBar(
                actions = listOf(
                    GamepadAction(GamepadButton.A, R.string.action_select),
                    GamepadAction(GamepadButton.B, R.string.back, onBack),
                ),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun AchievementHeader(
    gameName: String,
    unlocked: Int,
    total: Int,
    onBack: () -> Unit,
) {
    val progress = unlocked.toFloat() / total.coerceAtLeast(1)
    val compact = PluviaTheme.tokens.densityCompact
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = if (compact) 12.dp else 22.dp,
            vertical = if (compact) 4.dp else 14.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
    ) {
        ConsoleIconButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.achievements),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = gameName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.weight(0.55f),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = stringResource(R.string.achievements_count, unlocked, total),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .widthIn(max = 250.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PluviaTheme.colors.statusInstalled,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun AchievementFilters(
    filter: AchievementFilter,
    onFilter: (AchievementFilter) -> Unit,
) {
    val compact = PluviaTheme.tokens.densityCompact
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = if (compact) 12.dp else 22.dp,
            vertical = if (compact) 2.dp else 12.dp,
        ).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        AchievementFilter.entries.forEach { item ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val label = when (item) {
                AchievementFilter.ALL -> stringResource(R.string.achievements_filter_all)
                AchievementFilter.UNLOCKED -> stringResource(R.string.achievements_filter_unlocked)
                AchievementFilter.LOCKED -> stringResource(R.string.achievements_filter_locked)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRing(interactionSource, shape)
                    .clip(shape)
                    .background(
                        if (isFocused) MaterialTheme.colorScheme.surfaceContainerHigh
                        else Color.Transparent,
                    )
                    .selectable(filter == item, interactionSource, null) { onFilter(item) }
                    .padding(horizontal = if (compact) 6.dp else 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (filter == item) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (filter == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(
                                if (filter == item) MaterialTheme.colorScheme.primary else Color.Transparent,
                                RoundedCornerShape(1.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AchievementList(
    achievements: List<Achievement>,
    hiddenCount: Int,
    onRevealSecrets: () -> Unit,
    selected: Achievement?,
    onSelected: (Achievement) -> Unit,
    modifier: Modifier = Modifier,
    rarity: Map<String, Float> = emptyMap(),
    showDescriptions: Boolean = false,
) {
    val compact = PluviaTheme.tokens.densityCompact
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(achievements.isNotEmpty()) {
        if (achievements.isNotEmpty()) {
            repeat(3) {
                try {
                    if (firstRowFocus.requestFocus()) return@LaunchedEffect
                } catch (_: IllegalStateException) {}
                kotlinx.coroutines.delay(80)
            }
        }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = if (compact) 12.dp else 22.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        items(achievements, key = { it.name ?: it.displayName }) { achievement ->
            AchievementListRow(
                achievement = achievement,
                selected = achievement == selected,
                showDescription = showDescriptions,
                onClick = { onSelected(achievement) },
                onFocused = { if (!showDescriptions) onSelected(achievement) },
                rarityPercent = achievement.name?.let { rarity[it] },
                focusRequester = if (achievement == achievements.first()) firstRowFocus else null,
            )
        }
        if (hiddenCount > 0) {
            item(key = "hidden-achievements") {
                RevealSecretsRow(count = hiddenCount, onClick = onRevealSecrets)
            }
        }
        item { Spacer(Modifier.height(if (compact) 6.dp else 18.dp)) }
    }
}

@Composable
private fun AchievementListRow(
    achievement: Achievement,
    selected: Boolean,
    showDescription: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    rarityPercent: Float? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val compact = PluviaTheme.tokens.densityCompact
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusRing(interactionSource, shape)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .selectable(selected, interactionSource, null, onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AchievementIcon(achievement, if (compact) 36 else 48)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = achievement.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (achievement.isUnlocked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = if (showDescription) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showDescription && achievement.description.isNotBlank()) {
                Text(
                    text = achievement.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (achievement.hasProgress && !achievement.isUnlocked) {
                AchievementProgress(achievement, compact = true)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (achievement.isUnlocked) Icons.Default.EmojiEvents else Icons.Default.Lock,
                contentDescription = stringResource(
                    if (achievement.isUnlocked) R.string.achievements_filter_unlocked else R.string.achievements_locked,
                ),
                tint = if (achievement.isUnlocked) PluviaTheme.colors.statusInstalled else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            if (rarityPercent != null) {
                Text(
                    text = stringResource(R.string.achievements_rarity_short, rarityPercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AchievementDetails(
    achievement: Achievement?,
    rarity: Float? = null,
    modifier: Modifier = Modifier,
) {
    val compact = PluviaTheme.tokens.densityCompact
    val scrollState = remember(achievement?.name, achievement?.displayName) { ScrollState(0) }
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = if (compact) 16.dp else 28.dp, vertical = if (compact) 8.dp else 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (achievement == null) {
            Text(
                text = stringResource(R.string.achievements_select_one),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        AchievementIcon(achievement, if (compact) 64 else 88)
        Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
        Text(
            text = achievement.displayName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (achievement.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = achievement.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
        if (achievement.hasProgress && !achievement.isUnlocked) AchievementProgress(achievement)
        val unlockedAt = achievement.formattedUnlockDateTime()
        Text(
            text = if (unlockedAt != null) {
                stringResource(R.string.achievements_unlocked_at, unlockedAt.first, unlockedAt.second)
            } else {
                stringResource(R.string.achievements_locked)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (achievement.isUnlocked) PluviaTheme.colors.statusInstalled else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (rarity != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.achievements_unlocked_by_percent, rarity),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AchievementIcon(achievement: Achievement, size: Int) {
    val url = if (achievement.isUnlocked) achievement.icon else achievement.iconGray ?: achievement.icon
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isBlank()) {
            Icon(Icons.Default.EmojiEvents, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CoilImage(
                imageModel = { url },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AchievementProgress(achievement: Achievement, compact: Boolean = false) {
    val current = achievement.progressCurrent ?: return
    val maximum = achievement.progressMax ?: return
    val progress = (current / maximum).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(top = if (compact) 6.dp else 0.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(if (compact) 4.dp else 6.dp).clip(RoundedCornerShape(3.dp)),
        )
        if (!compact) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(R.string.achievements_progress, current.roundToInt(), maximum.roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun RevealSecretsRow(count: Int, onClick: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val compact = PluviaTheme.tokens.densityCompact
    val shape = RoundedCornerShape(PluviaTheme.tokens.cornerMd)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(interactionSource, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .selectable(false, interactionSource, null, onClick = onClick)
            .padding(if (compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(
                text = stringResource(R.string.achievements_hidden_count, count),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.achievements_hidden_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
