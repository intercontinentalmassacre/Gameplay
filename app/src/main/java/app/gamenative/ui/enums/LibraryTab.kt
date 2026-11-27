package app.gamenative.ui.enums

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import app.gamenative.R
import app.gamenative.itch.ItchPaths
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.SteamUtils

enum class LibraryTab(
    @get:StringRes val labelResId: Int,
    val showCustom: Boolean,
    val showSteam: Boolean,
    val showGoG: Boolean,
    val showEpic: Boolean,
    val showAmazon: Boolean,
    val showItch: Boolean,
    val installedOnly: Boolean,
    val icon: ImageVector? = null,
    @get:StringRes val emptyMessageResId: Int = R.string.library_no_results,
    val fixedSortOption: SortOption? = null,
) {
    INSTALLED(
        labelResId = R.string.tab_installed,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        showItch = true,
        installedOnly = true,
    ),
    RECENT(
        labelResId = R.string.tab_recent,
        emptyMessageResId = R.string.library_recent_no_results,
        fixedSortOption = SortOption.RECENTLY_PLAYED,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        showItch = true,
        installedOnly = false,
    ),
    FAVORITES(
        labelResId = R.string.tab_favorites,
        emptyMessageResId = R.string.library_favorites_no_results,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        showItch = true,
        installedOnly = false,
    ),
    ALL(
        labelResId = R.string.tab_all,
        showCustom = true,
        showSteam = true,
        showGoG = true,
        showEpic = true,
        showAmazon = true,
        showItch = true,
        installedOnly = false,
    ),
    STEAM(
        labelResId = R.string.tab_steam,
        showCustom = false,
        showSteam = true,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        showItch = false,
        installedOnly = false,
    ),
    GOG(
        labelResId = R.string.tab_gog,
        showCustom = false,
        showSteam = false,
        showGoG = true,
        showEpic = false,
        showAmazon = false,
        showItch = false,
        installedOnly = false,
    ),
    EPIC(
        labelResId = R.string.tab_epic,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = true,
        showAmazon = false,
        showItch = false,
        installedOnly = false,
    ),
    AMAZON(
        labelResId = R.string.tab_amazon,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = true,
        showItch = false,
        installedOnly = false,
    ),
    ITCH(
        labelResId = R.string.tab_itch,
        showCustom = false,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        showItch = true,
        installedOnly = false,
    ),
    LOCAL(
        labelResId = R.string.tab_local,
        showCustom = true,
        showSteam = false,
        showGoG = false,
        showEpic = false,
        showAmazon = false,
        showItch = false,
        installedOnly = false,
    );

    internal fun matchesEntry(appId: String, lastPlayed: Long, favoriteGameIds: Set<String>): Boolean = when (this) {
        RECENT -> lastPlayed > 0L
        FAVORITES -> appId in favoriteGameIds
        else -> true
    }

    companion object {
        /**
         * Tabs shown in the UI. Custom (LOCAL) games work on all flavors: legacy maps folders
         * in place via all-files access, modern imports them into app-owned storage.
         */
        fun visibleEntries(context: Context, itchLoggedIn: Boolean = ItchPaths.hasCredentials(context)): List<LibraryTab> = entries.filter { tab ->
            when (tab) {
                STEAM -> SteamUtils.hasStoredCredentials()
                GOG -> GOGService.hasStoredCredentials(context)
                EPIC -> EpicService.hasStoredCredentials(context)
                AMAZON -> AmazonService.hasStoredCredentials(context)
                ITCH -> itchLoggedIn
                LOCAL -> true
                INSTALLED, ALL, RECENT, FAVORITES -> true
            }
        }

        fun LibraryTab.next(context: Context): LibraryTab {
            val values = visibleEntries(context)
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[(index + 1) % values.size]
        }

        fun LibraryTab.previous(context: Context): LibraryTab {
            val values = visibleEntries(context)
            val index = values.indexOf(this).coerceAtLeast(0)
            return values[if (index == 0) values.size - 1 else index - 1]
        }
    }
}
