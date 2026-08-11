package app.gamenative.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

@Serializable
data class GameplayThemeDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val name: String,
    val dark: GameplayThemePalette,
    val light: GameplayThemePalette,
    val tokens: GameplayThemeTokens = GameplayThemeTokens(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val MIN_SCHEMA_VERSION = 1
    }
}

@Serializable
data class GameplayThemeTokens(
    val focusRingWidthDp: Int = 2,
    val focusRingStyle: String = "solid",
    /** Raises text, border and focus contrast without replacing the palette. */
    val highContrast: Boolean = false,
    val motionInstantMs: Int = 80,
    val motionFastMs: Int = 150,
    val motionNormalMs: Int = 220,
    val cornerSmDp: Int = 6,
    val cornerMdDp: Int = 10,
    val cornerLgDp: Int = 16,
    val elevationModalDp: Int = 12,
    val density: String = "compact",
    /** Geometry for console side panels and menus. */
    val panelMaxWidthDp: Int = 460,
    val panelHorizontalPaddingDp: Int = 28,
    val panelVerticalPaddingDp: Int = 20,
    val menuItemGapDp: Int = 5,
)

@Serializable
data class GameplayThemePalette(
    val primary: String,
    val onPrimary: String,
    val background: String,
    val onBackground: String,
    val surface: String,
    val surfaceElevated: String,
    val onSurface: String,
    val textMuted: String,
    val border: String,
    val success: String,
    val warning: String,
    val danger: String,

    // GCDS v2 surface roles
    val surfaceOverlay: String? = null,
    val surfaceGlass: String? = null,
    val surfaceScrim: String? = null,

    // GCDS v2 status roles
    val statusInstalled: String? = null,
    val statusDownloading: String? = null,
    val statusAvailable: String? = null,
    val statusAway: String? = null,
    val statusOffline: String? = null,

    // GCDS v2 compatibility roles (fg; bg derived by the provider)
    val compatGood: String? = null,
    val compatPartial: String? = null,
    val compatUnknown: String? = null,
    val compatBad: String? = null,

    // GCDS v2 gamercard rarity
    val rarityCommon: String? = null,
    val rarityRare: String? = null,
    val rarityUltraRare: String? = null,

    // GCDS v2 focus ring
    val focusRingColor: String? = null,
)

sealed interface GameplayThemeDecodeResult {
    data class Success(val document: GameplayThemeDocument) : GameplayThemeDecodeResult
    data class Error(val reason: String) : GameplayThemeDecodeResult
}

data class GameplayThemeWarning(val tokenPath: String)

object GameplayThemeCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun decode(source: String): GameplayThemeDecodeResult {
        val document = runCatching { json.decodeFromString<GameplayThemeDocument>(source) }
            .getOrElse { return GameplayThemeDecodeResult.Error("Invalid JSON or missing theme fields") }
        return validate(document)?.let(GameplayThemeDecodeResult::Error)
            ?: GameplayThemeDecodeResult.Success(document)
    }

    fun encode(document: GameplayThemeDocument): String = json.encodeToString(document)

    /** Non-blocking craft warnings; unlike validation errors these never reject a theme. */
    fun warnings(document: GameplayThemeDocument): List<GameplayThemeWarning> = buildList {
        addAcidicWarnings("dark", document.dark)
        addAcidicWarnings("light", document.light)
    }

    fun safeDocument(name: String = "Gameplay Slate"): GameplayThemeDocument = GameplayThemeDocument(
        name = name,
        dark = GameplayThemePalette(
            primary = "#6F91AA",
            onPrimary = "#FFFFFF",
            background = "#101419",
            onBackground = "#F2F5F7",
            surface = "#171D24",
            surfaceElevated = "#202831",
            onSurface = "#F2F5F7",
            textMuted = "#B4BEC8",
            border = "#3A4651",
            success = "#5B9B78",
            warning = "#C39A5C",
            danger = "#C56D6C",
            surfaceOverlay = "#202831",
            surfaceGlass = "#202831",
            surfaceScrim = "#000000",
            statusInstalled = "#5B9B78",
            statusDownloading = "#6D9EB8",
            statusAvailable = "#698FBA",
            statusAway = "#C39A5C",
            statusOffline = "#7F8993",
            compatGood = "#73AE8D",
            compatPartial = "#C6A76A",
            compatUnknown = "#9AA5AF",
            compatBad = "#D07A78",
            rarityCommon = "#9AA5AF",
            rarityRare = "#79A4BD",
            rarityUltraRare = "#C39A5C",
            focusRingColor = "#79A4BD",
        ),
        light = GameplayThemePalette(
            primary = "#315E78",
            onPrimary = "#FFFFFF",
            background = "#F7F9FA",
            onBackground = "#182026",
            surface = "#F0F3F5",
            surfaceElevated = "#E4E9ED",
            onSurface = "#182026",
            textMuted = "#4E5B66",
            border = "#69757D",
            success = "#397253",
            warning = "#805E22",
            danger = "#913F42",
            surfaceOverlay = "#E4E9ED",
            surfaceGlass = "#E4E9ED",
            surfaceScrim = "#000000",
            statusInstalled = "#397253",
            statusDownloading = "#315E78",
            statusAvailable = "#315E78",
            statusAway = "#805E22",
            statusOffline = "#5A6570",
            compatGood = "#2E6A4F",
            compatPartial = "#8A6A28",
            compatUnknown = "#5A6570",
            compatBad = "#913F42",
            rarityCommon = "#5A6570",
            rarityRare = "#315E78",
            rarityUltraRare = "#805E22",
            focusRingColor = "#315E78",
        ),
    )

    fun color(value: String): Color {
        val rgb = value.removePrefix("#").toLong(16)
        return Color(0xFF000000 or rgb)
    }

    private fun validate(document: GameplayThemeDocument): String? {
        if (document.schemaVersion !in GameplayThemeDocument.MIN_SCHEMA_VERSION..GameplayThemeDocument.CURRENT_SCHEMA_VERSION) {
            return "Unsupported theme schema version ${document.schemaVersion}"
        }
        if (document.name.trim().length !in 1..48) return "Theme name must contain 1 to 48 characters"
        if (document.tokens.focusRingWidthDp !in 1..8) return "focusRingWidth must be 1 to 8 dp"
        if (document.tokens.focusRingStyle !in setOf("solid", "dashed")) {
            return "focusRingStyle must be solid or dashed"
        }
        if (document.tokens.motionInstantMs <= 0 || document.tokens.motionFastMs <= 0 || document.tokens.motionNormalMs <= 0) {
            return "motion durations must be positive"
        }
        if (document.tokens.density !in setOf("compact", "comfortable")) {
            return "density must be compact or comfortable"
        }
        if (document.tokens.panelMaxWidthDp !in 320..640) {
            return "panelMaxWidthDp must be 320 to 640 dp"
        }
        if (document.tokens.panelHorizontalPaddingDp !in 12..48) {
            return "panelHorizontalPaddingDp must be 12 to 48 dp"
        }
        if (document.tokens.panelVerticalPaddingDp !in 8..40) {
            return "panelVerticalPaddingDp must be 8 to 40 dp"
        }
        if (document.tokens.menuItemGapDp !in 2..24) {
            return "menuItemGapDp must be 2 to 24 dp"
        }

        return validatePalette("dark", document.dark) ?: validatePalette("light", document.light)
    }

    private fun validatePalette(label: String, palette: GameplayThemePalette): String? {
        val fields = mapOf(
            "primary" to palette.primary,
            "onPrimary" to palette.onPrimary,
            "background" to palette.background,
            "onBackground" to palette.onBackground,
            "surface" to palette.surface,
            "surfaceElevated" to palette.surfaceElevated,
            "onSurface" to palette.onSurface,
            "textMuted" to palette.textMuted,
            "border" to palette.border,
            "success" to palette.success,
            "warning" to palette.warning,
            "danger" to palette.danger,
            "surfaceOverlay" to palette.surfaceOverlay,
            "surfaceGlass" to palette.surfaceGlass,
            "surfaceScrim" to palette.surfaceScrim,
            "statusInstalled" to palette.statusInstalled,
            "statusDownloading" to palette.statusDownloading,
            "statusAvailable" to palette.statusAvailable,
            "statusAway" to palette.statusAway,
            "statusOffline" to palette.statusOffline,
            "compatGood" to palette.compatGood,
            "compatPartial" to palette.compatPartial,
            "compatUnknown" to palette.compatUnknown,
            "compatBad" to palette.compatBad,
            "rarityCommon" to palette.rarityCommon,
            "rarityRare" to palette.rarityRare,
            "rarityUltraRare" to palette.rarityUltraRare,
            "focusRingColor" to palette.focusRingColor,
        )
        fields.entries.firstOrNull { it.value != null && !HEX_COLOR.matches(it.value!!) }?.let {
            return "$label.${it.key} must use #RRGGBB"
        }

        if (contrast(palette.onBackground, palette.background) < 4.5) {
            return "$label.onBackground does not have enough contrast against background"
        }
        if (contrast(palette.onSurface, palette.surface) < 4.5) {
            return "$label.onSurface does not have enough contrast against surface"
        }
        if (contrast(palette.onPrimary, palette.primary) < 3.0) {
            return "$label.onPrimary does not have enough contrast against primary"
        }
        if (contrast(palette.textMuted, palette.background) < 3.0) {
            return "$label.textMuted does not have enough contrast against background"
        }
        palette.focusRingColor?.let { focus ->
            if (contrast(focus, palette.background) < 3.0) {
                return "$label.focusRingColor does not have enough contrast against background"
            }
        }

        val semanticColors = mapOf(
            "statusInstalled" to palette.statusInstalled,
            "statusDownloading" to palette.statusDownloading,
            "statusAvailable" to palette.statusAvailable,
            "statusAway" to palette.statusAway,
            "statusOffline" to palette.statusOffline,
            "compatGood" to palette.compatGood,
            "compatPartial" to palette.compatPartial,
            "compatUnknown" to palette.compatUnknown,
            "compatBad" to palette.compatBad,
            "rarityCommon" to palette.rarityCommon,
            "rarityRare" to palette.rarityRare,
            "rarityUltraRare" to palette.rarityUltraRare,
        )
        semanticColors.forEach { (name, value) ->
            if (value != null && contrast(value, palette.surface) < 3.0) {
                return "$label.$name does not have enough contrast against surface"
            }
        }
        return null
    }

    private fun contrast(first: String, second: String): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun luminance(value: String): Double {
        val rgb = value.removePrefix("#").toLong(16)
        fun channel(shift: Int): Double {
            val component = ((rgb shr shift) and 0xFF).toDouble() / 255.0
            return if (component <= 0.04045) component / 12.92 else ((component + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun MutableList<GameplayThemeWarning>.addAcidicWarnings(
        paletteName: String,
        palette: GameplayThemePalette,
    ) {
        mapOf(
            "primary" to palette.primary,
            "focusRingColor" to palette.focusRingColor,
        ).forEach { (name, value) ->
            if (value != null && HEX_COLOR.matches(value) && isAcidic(value)) {
                add(GameplayThemeWarning("$paletteName.$name"))
            }
        }
    }

    private fun isAcidic(value: String): Boolean {
        val rgb = value.removePrefix("#").toLong(16)
        val red = ((rgb shr 16) and 0xFF).toDouble() / 255.0
        val green = ((rgb shr 8) and 0xFF).toDouble() / 255.0
        val blue = (rgb and 0xFF).toDouble() / 255.0
        val highest = max(red, max(green, blue))
        val lowest = min(red, min(green, blue))
        val saturation = if (highest == 0.0) 0.0 else (highest - lowest) / highest
        return saturation >= 0.90 && luminance(value) >= 0.55
    }

    private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")
}
