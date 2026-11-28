package app.gamenative.shadercache

import java.io.File
import java.security.MessageDigest

/**
 * Which pipelines a container no longer needs replayed on a driver, so a pool that grew is replayed only for what it
 * gained. Kept as Fossilize databases of empty entries, the format of fossilize-replay's --replayer-cache:
 *
 *   replayed-<driver id>.<pipeline cache uuid>.foz       read by fossilize-replay, which skips the pipelines in it
 *   replayed-<driver id>.<pipeline cache uuid>.<n>.foz   written by each fossilize-replay process; [fold] merges them
 *
 * Callers serialize access to a directory.
 */
internal object ReplayedPipelines {
    private const val FLAG_RAW = 1

    // Graphics, compute and raytracing pipelines.
    private val PIPELINE_TAGS = listOf(6, 7, 9).map { "%0${FozArchive.TAG_CHARS}x".format(it) }.toSet()

    /** The path to give fossilize-replay as --replayer-cache for [driver]; it appends the uuid itself. */
    fun cachePath(dir: File, driver: String): File = File(dir, prefix(driver))

    /** The names (tag and hash) of the pipeline entries in the Fossilize database [file]; empty if it is not one. */
    fun pipelineNames(file: File): Set<String> =
        FozArchive.names(file).filterTo(HashSet()) { it.substring(0, FozArchive.TAG_CHARS) in PIPELINE_TAGS }

    fun pending(databases: List<File>, known: Set<String>): Set<String> =
        databases.flatMapTo(HashSet()) { database -> pipelineNames(database).filterNot(known::contains) }

    fun writeWhitelist(file: File, pending: Set<String>): Boolean = write(file, pending)

    fun recorded(dir: File, driver: String): Set<String> =
        files(dir, driver).flatMapTo(HashSet()) { pipelineNames(it.first) }

    fun pendingIdentity(pending: Set<String>): String =
        MessageDigest.getInstance("SHA-256").digest(pending.sorted().joinToString("").toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }

    fun isStalled(file: File, pendingIdentity: String): Boolean =
        file.isFile && file.readText() == "2 $pendingIdentity"

    fun recordAttempt(file: File, pendingIdentity: String, progressed: Boolean) {
        if (progressed) {
            file.delete()
            return
        }
        val attempt = if (file.isFile && file.readText() == "1 $pendingIdentity") 2 else 1
        file.writeText("$attempt $pendingIdentity")
    }

    /** Only skip starting Fossilize for entries present in every known device UUID cache. */
    fun known(dir: File, driver: String): Set<String> {
        val caches = files(dir, driver).groupBy { it.second }.values.iterator()
        if (!caches.hasNext()) return emptySet()
        fun names(cache: List<Pair<File, String>>) = cache.flatMapTo(HashSet()) { pipelineNames(it.first) }
        val common = names(caches.next())
        while (caches.hasNext() && common.isNotEmpty()) common.retainAll(names(caches.next()))
        return common
    }

    /**
     * Merges what fossilize-replay processes wrote for [driver] into the database the next one reads.
     */
    fun fold(dir: File, driver: String) {
        val prefix = prefix(driver)
        val runs = files(dir, driver).mapNotNull { (file, uuid) ->
            if (file.name == "$prefix.$uuid.foz") null else uuid to file
        }
        for ((uuid, written) in runs.groupBy({ it.first }, { it.second })) {
            val base = File(dir, "$prefix.$uuid.foz")
            if (write(base, pipelineNames(base) + written.flatMap { pipelineNames(it) })) written.forEach { it.delete() }
        }
    }

    private fun prefix(driver: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(driver.toByteArray())
        return "replayed-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun files(dir: File, driver: String): List<Pair<File, String>> {
        val pattern = Regex("${prefix(driver)}\\.([0-9a-f]{32})(?:\\.\\d+)?\\.foz")
        return dir.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            pattern.matchEntire(file.name)?.let { file to it.groupValues[1] }
        }
    }

    /** Replaces [file] with a database of one empty entry per name; a reader sees the old or the new file. */
    private fun write(file: File, names: Set<String>): Boolean {
        val next = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            next.outputStream().buffered().use { out ->
                out.write(FozArchive.HEADER)
                val entryHeader = ByteArray(16).also { it[4] = FLAG_RAW.toByte() }
                for (name in names) {
                    out.write(name.toByteArray(Charsets.US_ASCII))
                    out.write(entryHeader)
                }
            }
            next.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            next.delete()
        }
    }
}
