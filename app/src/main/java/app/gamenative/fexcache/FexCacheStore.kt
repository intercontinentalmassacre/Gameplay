package app.gamenative.fexcache

import java.io.File
import org.json.JSONObject

enum class FexCacheState {
    NONE,
    PREPARING,
    COMPILING,
    READY,
    NEEDS_REPREPARE,
    FAILED,
}

/**
 * Identity of the inputs that FEX code generation depends on. FEX itself validates the cache
 * header against its own git hash, but the appended code cache does not contain the FEX preset
 * or the host CPU features, so Gameplay has to refuse a cache when any of those changed.
 */
data class FexCacheMetadata(
    val format: Int = FORMAT,
    val appId: String,
    val fexVersion: String,
    val fexBuildHash: String,
    val configHash: String,
    val deviceFingerprint: String,
    val executablePath: String,
    val executableSize: Long,
    val executableMtime: Long,
    val executableSha256: String,
    val preparedAt: Long,
    val prepareDurationMs: Long,
    val compileDurationMs: Long,
    val cacheBytes: Long,
) {
    fun compatibleWith(other: FexCacheMetadata): Boolean =
        format == other.format &&
            appId == other.appId &&
            fexVersion == other.fexVersion &&
            fexBuildHash == other.fexBuildHash &&
            configHash == other.configHash &&
            deviceFingerprint == other.deviceFingerprint &&
            executablePath == other.executablePath &&
            executableSize == other.executableSize &&
            executableMtime == other.executableMtime &&
            executableSha256 == other.executableSha256

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("appId", appId)
        put("fexVersion", fexVersion)
        put("fexBuildHash", fexBuildHash)
        put("configHash", configHash)
        put("deviceFingerprint", deviceFingerprint)
        put("executablePath", executablePath)
        put("executableSize", executableSize)
        put("executableMtime", executableMtime)
        put("executableSha256", executableSha256)
        put("preparedAt", preparedAt)
        put("prepareDurationMs", prepareDurationMs)
        put("compileDurationMs", compileDurationMs)
        put("cacheBytes", cacheBytes)
    }

    companion object {
        const val FORMAT = 1

        fun fromJson(json: JSONObject): FexCacheMetadata = FexCacheMetadata(
            format = json.optInt("format", 0),
            appId = json.optString("appId"),
            fexVersion = json.optString("fexVersion"),
            fexBuildHash = json.optString("fexBuildHash"),
            configHash = json.optString("configHash"),
            deviceFingerprint = json.optString("deviceFingerprint"),
            executablePath = json.optString("executablePath"),
            executableSize = json.optLong("executableSize"),
            executableMtime = json.optLong("executableMtime"),
            executableSha256 = json.optString("executableSha256"),
            preparedAt = json.optLong("preparedAt"),
            prepareDurationMs = json.optLong("prepareDurationMs"),
            compileDurationMs = json.optLong("compileDurationMs"),
            cacheBytes = json.optLong("cacheBytes"),
        )
    }
}

data class FexCacheStateRecord(
    val state: FexCacheState,
    val updatedAt: Long,
    val runStartedAt: Long = 0,
    val metadata: FexCacheMetadata? = null,
    val reason: String = "",
)

data class FexCacheStatus(
    val state: FexCacheState,
    val supported: Boolean,
    val cacheBytes: Long,
    val executablePath: String = "",
    val reason: String = "",
    val canPrepare: Boolean = false,
    val canClear: Boolean = false,
    val canRecompile: Boolean = false,
) {
    val isBusy: Boolean get() = state == FexCacheState.PREPARING || state == FexCacheState.COMPILING
}

/**
 * Per-game, on-disk state machine for the FEX translation cache. The cache files themselves are
 * written by FEX under [hostCacheDir]; this class only owns metadata, locking and publication.
 *
 * The methods are intentionally small and synchronous: callers run them on an IO dispatcher and
 * the file lock is the single source of truth for duplicate preparation attempts.
 */
class FexCacheStore(
    val appId: String,
    val rootDir: File,
    val hostCacheDir: File,
    val windowsCacheDir: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val stateFile = File(rootDir, "state.json")
    private val lockFile = File(rootDir, ".lock")

    init {
        require(windowsCacheDir.endsWith("\\")) { "FEX cache path must end with a backslash" }
    }

    fun readState(): FexCacheStateRecord {
        if (!stateFile.isFile) return FexCacheStateRecord(FexCacheState.NONE, 0)
        return runCatching {
            val json = JSONObject(stateFile.readText())
            FexCacheStateRecord(
                state = runCatching { FexCacheState.valueOf(json.getString("state")) }
                    .getOrDefault(FexCacheState.NONE),
                updatedAt = json.optLong("updatedAt"),
                runStartedAt = json.optLong("runStartedAt"),
                metadata = json.optJSONObject("metadata")?.let(FexCacheMetadata::fromJson),
                reason = json.optString("reason"),
            )
        }.getOrDefault(FexCacheStateRecord(FexCacheState.FAILED, 0, reason = "Unreadable cache state"))
    }

    fun writeState(record: FexCacheStateRecord) {
        rootDir.mkdirs()
        val json = JSONObject().apply {
            put("state", record.state.name)
            put("updatedAt", record.updatedAt)
            put("runStartedAt", record.runStartedAt)
            put("reason", record.reason)
            record.metadata?.let { put("metadata", it.toJson()) }
        }
        val tmp = File(rootDir, "state.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(stateFile)) {
            stateFile.delete()
            check(tmp.renameTo(stateFile)) { "Could not publish cache state" }
        }
    }

    fun beginPreparation(now: Long = clock()): FexCacheStateRecord {
        hostCacheDir.deleteRecursively()
        hostCacheDir.mkdirs()
        rootDir.mkdirs()
        val record = FexCacheStateRecord(FexCacheState.PREPARING, now)
        writeState(record)
        return record
    }

    fun markRunStarted(now: Long = clock()): FexCacheStateRecord {
        val current = readState()
        if (current.state != FexCacheState.PREPARING || current.runStartedAt != 0L) return current
        val updated = current.copy(runStartedAt = now, updatedAt = now)
        writeState(updated)
        return updated
    }

    fun markCompiling(now: Long = clock()): FexCacheStateRecord {
        val current = readState()
        val updated = current.copy(state = FexCacheState.COMPILING, updatedAt = now, reason = "")
        writeState(updated)
        return updated
    }

    fun markReady(metadata: FexCacheMetadata, now: Long = clock()): FexCacheStateRecord {
        val updated = FexCacheStateRecord(FexCacheState.READY, now, metadata = metadata)
        writeState(updated)
        return updated
    }

    fun markFailed(reason: String, now: Long = clock()): FexCacheStateRecord {
        val updated = FexCacheStateRecord(FexCacheState.FAILED, now, reason = reason)
        writeState(updated)
        return updated
    }

    fun markNeedsReprepare(reason: String, now: Long = clock()): FexCacheStateRecord {
        val updated = FexCacheStateRecord(FexCacheState.NEEDS_REPREPARE, now, reason = reason)
        writeState(updated)
        return updated
    }

    fun clear(): Boolean {
        val stateDeleted = !stateFile.exists() || stateFile.delete()
        val rootCleared = hostCacheDir.deleteRecursively()
        return stateDeleted && (rootCleared || !hostCacheDir.exists())
    }

    fun cacheBytes(): Long = hostCacheDir.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    fun hasCodeMap(): Boolean {
        val newMaps = File(hostCacheDir, "codemap/new")
        val readyMaps = File(hostCacheDir, "codemap/ready")
        return newMaps.listFiles()?.any { it.isFile && it.length() > 0 } == true ||
            readyMaps.listFiles()?.any { it.isFile && it.length() > 0 } == true
    }

    fun hasCacheArtifacts(): Boolean {
        val cacheDir = File(hostCacheDir, "cache")
        return cacheDir.listFiles()?.any { it.isFile && it.length() > 64 } == true
    }

    /** Returns a held lock, or null when another preparation already owns this game. */
    fun tryLock(): FexCacheLock? {
        rootDir.mkdirs()
        return FexCacheLock.acquire(lockFile)
    }
}

class FexCacheLock private constructor(
    private val file: java.io.RandomAccessFile,
    private val lock: java.nio.channels.FileLock,
) : AutoCloseable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { file.close() }
    }

    companion object {
        fun acquire(lockFile: File): FexCacheLock? {
            return runCatching {
                val file = java.io.RandomAccessFile(lockFile, "rw")
                val lock = file.channel.tryLock() ?: run {
                    file.close()
                    return null
                }
                FexCacheLock(file, lock)
            }.getOrNull()
        }
    }
}
