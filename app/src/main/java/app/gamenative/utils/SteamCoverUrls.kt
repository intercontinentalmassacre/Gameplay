package app.gamenative.utils

import app.gamenative.data.SteamApp
import app.gamenative.enums.Language

/**
 * Maps a [SteamApp] to the set of cover URLs we want to persist on disk.
 * Returns nullable URLs — the cache layer skips empty entries.
 */
object SteamCoverUrls {
    fun resolve(app: SteamApp): Map<CoverCache.Type, String?> {
        val lang = Language.english
        val out = mutableMapOf<CoverCache.Type, String?>()
        out[CoverCache.Type.HEADER] = app.headerUrl.takeIf { it.isNotBlank() }
        app.getCapsuleUrl(lang, large = false).takeIf { it.isNotBlank() }?.let {
            out[CoverCache.Type.CAPSULE] = it
        }
        app.getCapsuleUrl(lang, large = true).takeIf { it.isNotBlank() }?.let {
            out[CoverCache.Type.GRID_CAPSULE] = it
        }
        app.getHeroUrl(lang, large = false).takeIf { it.isNotBlank() }?.let {
            out[CoverCache.Type.HERO] = it
        }
        app.getHeroUrl(lang, large = true).takeIf { it.isNotBlank() }?.let {
            out[CoverCache.Type.GRID_HERO] = it
        }
        app.getLogoUrl(lang, large = false).takeIf { it != null && it.isNotBlank() }?.let {
            out[CoverCache.Type.LOGO] = it
        }
        if (app.iconHash.isNotBlank()) {
            out[CoverCache.Type.ICON] = app.iconUrl
        }
        return out
    }
}
