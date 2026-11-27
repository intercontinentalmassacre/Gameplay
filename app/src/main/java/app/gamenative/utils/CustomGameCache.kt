package app.gamenative.utils

import java.io.File
import kotlin.math.abs
import timber.log.Timber

/**
 * Manages caching of Custom Game app IDs and their folder paths.
 * Provides fast lookups and automatic invalidation when candidate folders change.
 */
internal object CustomGameCache {
    // Cache: appId (Int) -> folder path (String)
    private var appIdCache: Map<Int, String>? = null
    private var cachedFolders: Set<String>? = null

    /**
     * Builds the appId cache by scanning the supplied candidate folders.
     * Returns a map of appId (Int) -> folder path (String).
     */
    private fun buildCache(
        folders: Set<String>,
        readGameIdFromFile: (File) -> Int?
    ): Map<Int, String> {
        val cache = mutableMapOf<Int, String>()
        
        for (path in folders) {
            val folder = File(path)
            if (!folder.isDirectory) continue

            val folderId = readGameIdFromFile(folder)
                ?: abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }

            cache[folderId] = folder.absolutePath
        }

        Timber.tag("CustomGameCache").d("Built appId cache with ${cache.size} entries")
        return cache
    }

    /**
     * Gets or rebuilds the appId cache if needed.
     * Callers can share the same candidate snapshot throughout one scan.
     */
    @Synchronized
    fun getOrRebuildCache(
        folders: Set<String>,
        readGameIdFromFile: (File) -> Int?
    ): Map<Int, String> {
        val cached = appIdCache
        val result = if (cached != null && cachedFolders == folders) {
            cached
        } else {
            buildCache(folders, readGameIdFromFile)
        }
        appIdCache = result
        cachedFolders = folders
        return result
    }

    /**
     * Invalidates the appId cache, forcing a rebuild on next access.
     * Call this when Custom Game paths change, after deletion, or after manual refresh.
     */
    @Synchronized
    fun invalidate() {
        appIdCache = null
        cachedFolders = null
        Timber.tag("CustomGameCache").d("AppId cache invalidated")
    }

    /**
     * Updates the cache with a new entry.
     * Removes any stale entries with the same path but different appId to maintain consistency.
     * Used for incremental updates when scanning new games.
     */
    @Synchronized
    fun addEntry(appId: Int, folderPath: String) {
        val cached = appIdCache ?: return
        if (cached[appId] == folderPath) return
        appIdCache = cached.toMutableMap().apply {
            values.remove(folderPath)
            put(appId, folderPath)
        }
    }
}

