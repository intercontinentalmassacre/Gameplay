package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults

// Your custom color scheme
val customBackground = Color(0xFF101419)
val customForeground = Color(0xFFF2F5F7)
val customCard = Color(0xFF171D24)
val customCardForeground = Color(0xFFF2F5F7)
val customPrimary = Color(0xFF527595)
val customPrimaryForeground = Color(0xFFFFFFFF)
val customSecondary = Color(0xFF29333D)
val customSecondaryForeground = Color(0xFFF2F5F7)
val customMuted = Color(0xFF29333D)
val customMutedForeground = Color(0xFFB4BEC8)
val customAccent = Color(0xFF79A4BD)
val customAccentForeground = Color(0xFF101419)
val customDestructive = Color(0xFF71383D)

val pluviaSeedColor = Color(0xFF527595)

/**
 * Raw color primitives for the Pluvia app.
 * These are the base colors used to construct theme palettes.
 */

// Brand
val PluviaPrimary = Color(0xFF527595)
val PluviaSeed = Color(0xFF527595)

// Backgrounds
val PluviaBackground = Color(0xFF101419)
val PluviaSurface = Color(0xFF171D24)
val PluviaSurfaceElevated = Color(0xFF202831)
val PluviaCard = Color(0xFF171D24)

// Foregrounds
val PluviaForeground = Color(0xFFF2F5F7)
val PluviaForegroundMuted = Color(0xFFB4BEC8)

// Secondary
val PluviaSecondary = Color(0xFF29333D)

// Accents
val PluviaCyan = Color(0xFF79A4BD)
val PluviaPurple = Color(0xFF8FA5B7)
val PluviaPink = Color(0xFFB79691)

// Semantic
val PluviaSuccess = Color(0xFF5B9B78)
val PluviaWarning = Color(0xFFC39A5C)
val PluviaDanger = Color(0xFFC56D6C)
val PluviaDestructive = Color(0xFF71383D)

// Border
val PluviaBorder = Color(0xFF3A4651)

// Status - Installed/Download states
val StatusInstalled = Color(0xFF5B9B78)
val StatusDownloading = Color(0xFF6D9EB8)
val StatusAvailable = Color(0xFF698FBA)
val StatusAway = Color(0xFFC39A5C)
val StatusOffline = Color(0xFF7F8993)

// Friend states
val FriendOnline = Color(0xFF79A4BD)
val FriendOffline = Color(0xFF7F8993)
val FriendInGame = Color(0xFF6E9A7F)
val FriendAwayOrSnooze = Color(0x8079A4BD)
val FriendInGameAwayOrSnooze = Color(0x806E9A7F)
val FriendBlocked = Color(0xFF9B5558)

// Compatibility
val CompatibilityGood = Color(0xFF73AE8D)
val CompatibilityGoodBg = Color(0xFF244235)
val CompatibilityPartial = Color(0xFFC6A76A)
val CompatibilityPartialBg = Color(0xFF4A3D27)
val CompatibilityUnknown = Color(0xFF9AA5AF)
val CompatibilityUnknownBg = Color(0xFF343D46)
val CompatibilityBad = Color(0xFFD07A78)
val CompatibilityBadBg = Color(0xFF542F32)

// GCDS v2 surfaces
val PluviaSurfaceOverlay = Color(0xFF323232)
val PluviaSurfaceGlass = Color(0xFF232B33)
val PluviaSurfaceScrim = Color(0x99000000)

// GCDS v2 rarity (achievement unlock tiers)
val RarityCommon = Color(0xFF9AA5AF)
val RarityRare = Color(0xFF79A4BD)
val RarityUltraRare = Color(0xFFC39A5C)

// GCDS v2 focus ring
val FocusRingColor = Color(0xFF79A4BD)

/**
 * Swatch palettes (accent, surface, muted) shown in the built-in theme profile
 * picker. Kept as data in the theme package so settings only reference tokens.
 */
val BuiltInThemeSwatches: Map<String, List<Color>> = mapOf(
    "TonalSpot" to listOf(Color(0xFF6F91AA), Color(0xFF202831), Color(0xFFB4BEC8)),
    "Neutral" to listOf(Color(0xFF87939D), Color(0xFF252A2E), Color(0xFFC4CBD0)),
    "Vibrant" to listOf(Color(0xFF65A5C7), Color(0xFF17242B), Color(0xFFB8D2DF)),
    "Expressive" to listOf(Color(0xFF8F94C9), Color(0xFF222331), Color(0xFFCBCDE3)),
    "Rainbow" to listOf(Color(0xFF6F977E), Color(0xFF18231D), Color(0xFFB7C8BD)),
    "FruitSalad" to listOf(Color(0xFFA47B5D), Color(0xFF261F1A), Color(0xFFD1C0B3)),
    "Fidelity" to listOf(Color(0xFF967080), Color(0xFF251D21), Color(0xFFD0BCC5)),
    "Content" to listOf(Color(0xFF668B91), Color(0xFF172124), Color(0xFFB6C9CC)),
    "Monochrome" to listOf(Color.White, Color(0xFF101214), Color(0xFF737A80)),
)
