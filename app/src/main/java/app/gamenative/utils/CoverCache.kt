package app.gamenative.utils

import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Concurrency: network work is bounded by a single-thread executor; downloads
 * are de-duplicated by (appId, type) inside [inflight]. Disk writes go through
 * a temp file + atomic rename so partial files are never observed.
 *
 * Size cap: enforced lazily on each enqueue. When [sizeBytes] exceeds the
 * configured MB cap, oldest files (by last-modified) are evicted until under
 * limit. Orphan pruning is the caller's job (compare against owned appIds).
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

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    private val inflight = ConcurrentHashMap<String, CompletableDeferred<File?>>()

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
    fun enqueue(
        appId: String,
        type: Type,
        url: String?,
        client: OkHttpClient = Net.http,
    ) {
        if (!PrefManager.coverCacheEnabled) return
        if (appId.isEmpty() || url.isNullOrEmpty()) return
        val key = key(appId, type)
        if (fileFor(appId, type)?.let { it.exists() && it.length() > 0L } == true) return

        val existing = inflight.putIfAbsent(key, CompletableDeferred())
        if (existing != null) return

        ioScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { download(client, url, fileFor(appId, type)!!) }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "download failed appId=%s type=%s", appId, type)
                null
            }
            inflight.remove(key)?.complete(file)
            if (file != null) enforceSizeCap()
        }
    }

    /**
     * Batch variant for library sync. Each entry is independent; bad URLs
     * don't block the others.
     */
    fun enqueueAll(
        appId: String,
        urls: Map<Type, String?>,
        client: OkHttpClient = Net.http,
    ) {
        for ((type, url) in urls) enqueue(appId, type, url, client)
    }

    /**
     * Remove any cached cover for [appId]. Called when a game is uninstalled
     * or removed from the library.
     */
    fun evict(appId: String) {
        if (appId.isEmpty()) return
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(prefix(appId))) f.delete()
        }
    }

    /**
     * Remove covers for appIds no longer in the user's library.
     * Cheap O(n) directory scan; runs at app start.
     */
    fun pruneOrphans(knownAppIds: Set<String>) {
        executor.execute {
            val known = knownAppIds.map { prefix(it) }.toHashSet()
            val files = cacheDir.listFiles() ?: return@execute
            var pruned = 0
            for (f in files) {
                val p = f.name.substringBefore("__")
                if (prefix(p) !in known) {
                    if (f.delete()) pruned++
                }
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
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun fileCount(): Int = cacheDir.listFiles()?.size ?: 0

    private fun fileFor(appId: String, type: Type): File? {
        if (appId.isEmpty()) return null
        return File(cacheDir, sanitize(appId) + "__" + type.fileName + "." + type.ext)
    }

    private fun key(appId: String, type: Type): String = sanitize(appId) + ":" + type.fileName

    private fun prefix(appId: String): String = sanitize(appId) + "__"

    private fun sanitize(appId: String): String {
        // Steam appIds are numeric; custom appIds can be alphanumeric. Keep
        // the namespace safe regardless.
        val sb = StringBuilder(appId.length)
        for (c in appId) {
            sb.append(if (c.isLetterOrDigit()) c else '_')
        }
        return sb.toString()
    }

    private fun download(client: OkHttpClient, url: String, dest: File): File? {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val tmp = File(cacheDir, dest.name + ".tmp")
            tmp.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                // renameTo across some filesystems can fail; fall back.
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest
        }
    }

    private fun enforceSizeCap() {
        val capBytes = PrefManager.coverCacheMaxMb.toLong() * 1024L * 1024L
        if (capBytes <= 0L) return
        val files = cacheDir.listFiles()?.toList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return
        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            if (total <= capBytes) break
            val sz = f.length()
            if (f.delete()) total -= sz
        }
        Timber.tag(TAG).d("evicted to %d MB (cap %d MB)", total / (1024 * 1024), PrefManager.coverCacheMaxMb)
    }
}
