package app.gamenative.utils

import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.TreeSet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Persistent on-device cover store. Downloads cover/header/hero/grid images
 * for known apps to filesDir/covers/<appId>__<type>.<ext>. Returns file URI
 * when available so image loaders can serve from disk instantly and offline.
 *
 * Priority chain at the call sites:
 *   1. Custom media (user-picked)
 *   2. CoverCache (this) — file:// on disk
 *   3. SteamGridDB / Steam CDN URLs
 *
 * Concurrency: network work is bounded by a two-thread executor; downloads
 * are de-duplicated by (appId, type) inside [inflight]. Disk writes go through
 * a temp file + atomic rename so partial files are never observed.
 *
 * Size cap: enforced when a download is committed using a disk index guarded
 * by the same lock as [inflight]. Oldest files (by last-modified) are evicted
 * first. Orphan pruning is the caller's job (compare against owned appIds).
 */
object CoverCache {

    enum class Type(val fileName: String, val ext: String) {
        HEADER("header", "jpg"),
        CAPSULE("capsule", "jpg"),
        HERO("hero", "jpg"),
        GRID_HERO("grid_hero", "jpg"),
        GRID_CAPSULE("grid_capsule", "jpg"),
        LOGO("logo", "jpg"),
        ICON("icon", "jpg"),
    }

    private const val TAG = "CoverCache"
    private const val SCHEME_FILE = "file://"
    private const val TEMP_SUFFIX = ".tmp"

    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "CoverCache-IO").apply { isDaemon = true }
    }

    private val cacheDir: File by lazy {
        val tmpDir = System.getProperty("java.io.tmpdir") ?: "."
        val root = PluviaApp.get()?.filesDir ?: File(tmpDir)
        val dir = File(root, "covers")
        dir.mkdirs()
        dir
    }

    private val inflight = mutableMapOf<String, Download>()
    private val diskIndex by lazy { DiskIndex(cacheDir) }

    /**
     * Resolve a cached file URI for the given (appId, type). Returns null if
     * the file is not present on disk. Returned string is a `file://` URI
     * usable directly by Coil.
     */
    fun fileUri(appId: String, type: Type): String? {
        val file = fileFor(appId, type) ?: return null
        if (!file.exists() || file.length() == 0L) return null
        return SCHEME_FILE + file.absolutePath
    }

    /**
     * Returns a file:// URI for the first cached cover found for [appId]
     * across the priority order used by the home grid. Useful for backdrops
     * and ambient surfaces that don't care which flavor they get.
     */
    fun anyUri(appId: String): String? {
        val order = listOf(Type.GRID_HERO, Type.HEADER, Type.HERO, Type.GRID_CAPSULE, Type.CAPSULE, Type.ICON)
        for (t in order) {
            fileUri(appId, t)?.let { return it }
        }
        return null
    }

    /**
     * Enqueue a download. Idempotent: re-requests for the same key are
     * coalesced. Failures are logged and silently dropped; the cache stays
     * best-effort.
     */
    private fun enqueue(
        appId: String,
        type: Type,
        urls: List<String>,
        client: OkHttpClient,
    ) {
        if (!PrefManager.coverCacheEnabled) return
        if (appId.isEmpty() || urls.isEmpty()) return
        val key = key(appId, type)
        val dest = fileFor(appId, type) ?: return
        val download = Download(inflight, ::recordDownload)
        synchronized(inflight) {
            if (dest.isFile && dest.length() > 0L) return
            if (key in inflight) return
            inflight[key] = download
        }

        executor.execute {
            try {
                download.fetch(client, urls, dest)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "download failed appId=%s type=%s", appId, type)
            } finally {
                synchronized(inflight) {
                    if (inflight[key] === download) inflight.remove(key)
                }
            }
        }
    }

    /**
     * Batch variant for library sync. Each entry is independent; bad URLs
     * don't block the others.
     */
    fun enqueueAll(
        appId: String,
        urls: Map<Type, List<String>>,
        client: OkHttpClient = Net.http,
    ) {
        for ((type, candidates) in urls) enqueue(appId, type, candidates, client)
    }

    /**
     * Remove any cached cover for [appId]. Called when a game is uninstalled
     * or removed from the library.
     */
    fun evict(appId: String) {
        if (appId.isEmpty()) return
        synchronized(inflight) {
            val keyPrefix = sanitize(appId) + ":"
            inflight.entries.removeAll { (key, download) ->
                key.startsWith(keyPrefix).also { if (it) download.cancel() }
            }
            val filePrefix = prefix(appId)
            diskIndex.deleteWhere { it.name.startsWith(filePrefix) }
        }
    }

    /**
     * Remove covers for appIds no longer in the user's library.
     * Cheap O(n) directory scan; runs at app start.
     */
    fun pruneOrphans(knownAppIds: Set<String>) {
        executor.execute {
            val known = knownAppIds.map { prefix(it) }.toHashSet()
            val pruned = synchronized(inflight) {
                diskIndex.deleteWhere { prefix(it.name.substringBefore("__")) !in known }
            }
            if (pruned > 0) Timber.tag(TAG).d("pruned %d orphan covers", pruned)
        }
    }

    /**
     * Total bytes on disk across all cached covers.
     */
    fun sizeBytes(): Long {
        var total = 0L
        cacheDir.listFiles()?.forEach { total += it.length() }
        return total
    }

    fun clear() {
        synchronized(inflight) {
            inflight.values.forEach { it.cancel() }
            inflight.clear()
            diskIndex.deleteWhere { true }
            cacheDir.listFiles { _, name -> name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        }
    }

    fun fileCount(): Int = cacheDir.listFiles()?.size ?: 0

    private fun fileFor(appId: String, type: Type): File? {
        if (appId.isEmpty()) return null
        return File(cacheDir, sanitize(appId) + "__" + type.fileName + "." + type.ext)
    }

    private fun key(appId: String, type: Type): String = sanitize(appId) + ":" + type.fileName

    private fun prefix(appId: String): String = sanitize(appId) + "__"

    private fun sanitize(appId: String): String {
        // Steam sync writes numeric IDs and library items use source-prefixed IDs.
        val cacheId = appId.removePrefix("${GameSource.STEAM.name}_")
        val sb = StringBuilder(cacheId.length)
        for (c in cacheId) {
            sb.append(if (c.isLetterOrDigit()) c else '_')
        }
        return sb.toString()
    }

    internal class Download(
        private val lock: Any = Any(),
        private val onPublished: (File) -> Unit = {},
    ) {
        private var cancelled = false
        private var activeCall: Call? = null

        fun cancel() {
            synchronized(lock) {
                cancelled = true
                activeCall?.cancel()
            }
        }

        fun fetch(client: OkHttpClient, urls: List<String>, dest: File): File? {
            for (url in urls.distinct()) {
                val call = synchronized(lock) {
                    if (cancelled) return null
                    client.newCall(Request.Builder().url(url).build()).also {
                        it.timeout().timeout(10, TimeUnit.SECONDS)
                        activeCall = it
                    }
                }
                try {
                    download(call, dest)?.let { return it }
                } catch (e: IOException) {
                    if (!call.isCanceled()) Timber.tag(TAG).w(e, "cover download failed")
                } finally {
                    synchronized(lock) { activeCall = null }
                }
            }
            return null
        }

        private fun download(call: Call, dest: File): File? {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val tmp = File.createTempFile(dest.name, TEMP_SUFFIX, dest.parentFile)
                try {
                    tmp.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                    if (tmp.length() == 0L) return null
                    synchronized(lock) {
                        if (cancelled) return null
                        Files.move(tmp.toPath(), dest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                        onPublished(dest)
                    }
                    return dest
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    private fun recordDownload(file: File) {
        diskIndex.record(file)
        val capMb = PrefManager.coverCacheMaxMb
        val capBytes = capMb.toLong() * 1024L * 1024L
        if (capBytes > 0L && diskIndex.sizeBytes > capBytes) {
            diskIndex.trimTo(capBytes)
            Timber.tag(TAG).d("evicted to %d MB (cap %d MB)", diskIndex.sizeBytes / (1024 * 1024), capMb)
        }
    }

    internal class DiskIndex(directory: File) {
        private data class Entry(val file: File, val bytes: Long, val modified: Long)

        private val entries = mutableMapOf<File, Entry>()
        private val oldestFirst = TreeSet(compareBy<Entry> { it.modified }.thenBy { it.file.name })
        var sizeBytes = 0L
            private set

        init {
            directory.listFiles { _, name -> !name.endsWith(TEMP_SUFFIX) }?.forEach(::record)
        }

        fun record(file: File) {
            val entry = Entry(file, file.length(), file.lastModified())
            entries.put(file, entry)?.let { previous ->
                oldestFirst.remove(previous)
                sizeBytes -= previous.bytes
            }
            oldestFirst.add(entry)
            sizeBytes += entry.bytes
        }

        fun deleteWhere(matches: (File) -> Boolean): Int {
            var deleted = 0
            val iterator = entries.values.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (matches(entry.file) && entry.file.delete()) {
                    iterator.remove()
                    oldestFirst.remove(entry)
                    sizeBytes -= entry.bytes
                    deleted++
                }
            }
            return deleted
        }

        fun trimTo(capBytes: Long) {
            val iterator = oldestFirst.iterator()
            while (sizeBytes > capBytes && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.file.delete()) {
                    iterator.remove()
                    entries.remove(entry.file)
                    sizeBytes -= entry.bytes
                }
            }
        }
    }
}
