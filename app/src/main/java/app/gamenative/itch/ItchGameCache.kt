package app.gamenative.itch

import app.gamenative.data.ItchGame
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory snapshot of the itch.io rows, kept in sync by
 * [ItchLibraryRepository]. It exists because the library and app-screen
 * contracts ask for installed state synchronously from composition, where a
 * database round trip is not acceptable.
 */
object ItchGameCache {
    private val games = ConcurrentHashMap<Long, ItchGame>()

    fun put(game: ItchGame) {
        games[game.gameId] = game
    }

    fun putAll(items: List<ItchGame>) {
        items.forEach(::put)
    }

    fun get(gameId: Long): ItchGame? = games[gameId]

    fun remove(gameId: Long) {
        games.remove(gameId)
    }

    fun installed(gameId: Long): Boolean = games[gameId]?.isInstalled == true

    fun clear() {
        games.clear()
    }
}
