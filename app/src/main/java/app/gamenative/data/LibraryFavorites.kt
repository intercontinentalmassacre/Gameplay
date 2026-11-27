package app.gamenative.data

internal data class LibraryFavorites(
    val gameIds: Set<String> = emptySet(),
    val playNextGameId: String? = null,
) {
    fun toggleFavorite(gameId: String): LibraryFavorites =
        if (gameId in gameIds) {
            copy(gameIds = gameIds - gameId, playNextGameId = playNextGameId?.takeUnless { it == gameId })
        } else {
            copy(gameIds = gameIds + gameId)
        }

    fun togglePlayNext(gameId: String): LibraryFavorites =
        if (gameId in gameIds) copy(playNextGameId = gameId.takeUnless { it == playNextGameId }) else this
}
