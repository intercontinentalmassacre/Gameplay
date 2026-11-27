package app.gamenative.ui.model

internal object LibrarySortUtils {

    fun <T> recentlyPlayedComparator(
        name: (T) -> String,
        isInstalled: (T) -> Boolean,
        lastPlayed: (T) -> Long,
        installedFirst: Boolean = true,
    ): Comparator<T> {
        return compareBy<T> { entry ->
            if (installedFirst && !isInstalled(entry)) 1 else 0
        }.thenByDescending { entry ->
            lastPlayed(entry)
        }.thenBy { entry ->
            name(entry).lowercase()
        }
    }
}
