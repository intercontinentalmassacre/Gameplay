package app.gamenative.ui.screen.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
internal fun AchievementSummaryButton(
    achievements: List<Achievement>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unlocked = achievements.count(Achievement::isUnlocked)
    val progress = unlocked.toFloat() / achievements.size.coerceAtLeast(1)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Surface(
        modifier = modifier
            .focusRing(interactionSource, RoundedCornerShape(10.dp), width = 2.dp)
            .selectable(
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.achievements),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.achievements_count, unlocked, achievements.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                    color = PluviaTheme.colors.statusInstalled,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConsoleIconButton(
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.achievements),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = gameName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.achievements_count, unlocked, total),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AchievementFilters(
    filter: AchievementFilter,
    onFilter: (AchievementFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AchievementFilter.entries.forEachIndexed { index, item ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val label = when (item) {
                AchievementFilter.ALL -> stringResource(R.string.achievements_filter_all)
                AchievementFilter.UNLOCKED -> stringResource(R.string.achievements_filter_unlocked)
                AchievementFilter.LOCKED -> stringResource(R.string.achievements_filter_locked)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (filter == item) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                    .focusRing(interactionSource, RoundedCornerShape(8.dp), width = 2.dp)
                    .selectable(filter == item, interactionSource, null) { onFilter(item) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge)
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
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(achievements.isNotEmpty()) {
        if (achievements.isNotEmpty()) {
            repeat(3) {
                try {
                    firstRowFocus.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    kotlinx.coroutines.delay(80)
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(achievements, key = { it.name ?: it.displayName }) { achievement ->
            AchievementListRow(
                achievement = achievement,
                selected = achievement == selected,
                showDescription = showDescriptions,
                onClick = { onSelected(achievement) },
                rarityPercent = achievement.name?.let { rarity[it] },
                focusRequester = if (achievement == achievements.first()) firstRowFocus else null,
            )
        }
        if (hiddenCount > 0) {
            item(key = "hidden-achievements") {
                RevealSecretsRow(count = hiddenCount, onClick = onRevealSecrets)
            }
        }
        item { Spacer(Modifier.height(18.dp)) }
    }
}

@Composable
private fun AchievementListRow(
    achievement: Achievement,
    selected: Boolean,
    showDescription: Boolean,
    onClick: () -> Unit,
    rarityPercent: Float? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                },
            )
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .focusRing(interactionSource, RoundedCornerShape(9.dp), width = 2.dp)
            .selectable(selected, interactionSource, null, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AchievementIcon(achievement, 48)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = achievement.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (achievement.isUnlocked) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
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
                contentDescription = null,
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
    Column(
        modifier = modifier.padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        if (achievement == null) {
            Text(
                text = stringResource(R.string.achievements_select_one),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        AchievementIcon(achievement, 88)
        Spacer(Modifier.height(18.dp))
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
        Spacer(Modifier.height(18.dp))
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .focusRing(interactionSource, RoundedCornerShape(9.dp), width = 2.dp)
            .selectable(false, interactionSource, null, onClick = onClick)
            .padding(16.dp),
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
