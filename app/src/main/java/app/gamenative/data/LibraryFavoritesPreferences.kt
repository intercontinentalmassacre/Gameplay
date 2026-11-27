package app.gamenative.data

import android.content.SharedPreferences

private const val GAME_IDS = "game_ids"
private const val PLAY_NEXT_GAME_ID = "play_next_game_id"

internal fun SharedPreferences.readLibraryFavorites(): LibraryFavorites {
    val gameIds = getStringSet(GAME_IDS, emptySet()).orEmpty().toSet()
    val playNext = getString(PLAY_NEXT_GAME_ID, null)?.takeIf { it in gameIds }
    return LibraryFavorites(gameIds, playNext)
}

internal fun SharedPreferences.writeLibraryFavorites(favorites: LibraryFavorites) {
    edit()
        .putStringSet(GAME_IDS, favorites.gameIds)
        .putString(PLAY_NEXT_GAME_ID, favorites.playNextGameId)
        .apply()
}
