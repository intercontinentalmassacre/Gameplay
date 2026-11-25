package app.gamenative.itch

import java.util.concurrent.ConcurrentHashMap

/**
 * Small process-wide cache of itch.io titles, used by launch diagnostics and
 * container helpers that only receive an appId. It is populated whenever the
 * library cache is read or written, so it never triggers I/O or network by
 * itself.
 */
object ItchNameCache {
    private val titles = ConcurrentHashMap<Long, String>()

    fun put(gameId: Long, title: String) {
        if (gameId > 0 && title.isNotBlank()) titles[gameId] = title
    }

    fun titleFor(gameId: Long): String? = titles[gameId]

    fun clear() {
        titles.clear()
    }
}
