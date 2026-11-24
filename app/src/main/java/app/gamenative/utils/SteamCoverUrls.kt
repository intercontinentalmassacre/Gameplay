package app.gamenative.utils

import app.gamenative.data.SteamApp
import app.gamenative.enums.Language

/**
 * Maps a [SteamApp] to the set of cover URLs we want to persist on disk.
 * Candidates are ordered from preferred artwork to a legacy CDN or header.
 */
object SteamCoverUrls {
    fun resolve(app: SteamApp): Map<CoverCache.Type, List<String>> {
        val lang = Language.english
        val out = mutableMapOf<CoverCache.Type, List<String>>()
        val modern = app.headerUrl.substringBeforeLast('/')
        val legacy = "https://cdn.akamai.steamstatic.com/steam/apps/${app.id}"
        val headers = (listOfNotNull(app.getHeaderImageUrl(lang)) + app.headerUrl + "$legacy/header.jpg").distinct()
        fun candidates(metadata: List<String?>, filename: String): List<String> =
            ((metadata.filterNotNull() + "$modern/$filename").filter { it.isNotBlank() }
                .flatMap { listOf(it, "$legacy/${it.removePrefix("$modern/")}") } + headers).distinct()

        val capsule = app.libraryAssets.libraryCapsule
        val capsuleUrl = app.getCapsuleUrl(lang).takeIf { capsule.image.isNotEmpty() }
        val largeCapsuleUrl = app.getCapsuleUrl(lang, large = true).takeIf { capsule.image2x.isNotEmpty() }
        val hero = app.libraryAssets.libraryHero
        val heroUrl = app.getHeroUrl(lang).takeIf { hero.image.isNotEmpty() }
        val largeHeroUrl = app.getHeroUrl(lang, large = true).takeIf { hero.image2x.isNotEmpty() }

        out[CoverCache.Type.HEADER] = headers
        out[CoverCache.Type.CAPSULE] = candidates(listOf(capsuleUrl), "library_600x900.jpg")
        out[CoverCache.Type.GRID_CAPSULE] = candidates(listOf(largeCapsuleUrl, capsuleUrl), "library_600x900_2x.jpg")
        out[CoverCache.Type.HERO] = candidates(listOf(heroUrl), "library_hero.jpg")
        out[CoverCache.Type.GRID_HERO] = candidates(listOf(largeHeroUrl, heroUrl), "library_hero_2x.jpg")
        app.getLogoUrl(lang, large = false).takeIf { it != null && it.isNotBlank() }?.let {
            out[CoverCache.Type.LOGO] = listOf(it)
        }
        if (app.iconHash.isNotBlank()) {
            out[CoverCache.Type.ICON] = listOf(app.iconUrl)
        }
        return out
    }

}
