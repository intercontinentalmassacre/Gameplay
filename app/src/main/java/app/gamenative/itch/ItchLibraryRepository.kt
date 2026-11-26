package app.gamenative.itch

import app.gamenative.data.ItchGame
import app.gamenative.db.dao.ItchGameDao
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ItchNotAuthenticatedException :
    IllegalStateException("Connect an itch.io account to use the library")

data class ItchLibraryRefresh(
    val ownedGames: Int,
    val pagesFetched: Int,
    val accountLabel: String,
)

/** One upload list plus the selection decision for a project. */
data class ItchUploadSelection(
    val gameId: Long,
    val uploads: List<ItchUpload>,
    val choice: ItchUploadChoice,
)

/**
 * Account-scoped itch.io library cache and update bookkeeping.
 *
 * The cache is bound to the connected account: when a different account signs in,
 * account-derived rows from the previous account are dropped, and signing out
 * deletes every non-installed row while installed games are kept launchable.
 */
@Singleton
class ItchLibraryRepository @Inject constructor(
    private val dao: ItchGameDao,
    private val auth: ItchAuthManager,
    private val api: ItchApiClient,
) {
    fun observeGames(): Flow<List<ItchGame>> = dao.observeAll()

    fun hasStoredCredentials(): Boolean = auth.hasStoredCredentials()

    fun account(): ItchAccount? = auth.accountOrNull()

    fun isConfigured(): Boolean = auth.isConfigured()

    suspend fun getGame(gameId: Long): ItchGame? = dao.getByGameId(gameId)?.also { game ->
        ItchNameCache.put(game.gameId, game.title)
        ItchGameCache.put(game)
    }

    suspend fun installedGames(): List<ItchGame> = dao.getInstalledGames().also { games ->
        games.forEach { game ->
            ItchNameCache.put(game.gameId, game.title)
            ItchGameCache.put(game)
        }
    }

    /** Pulls every page of `/profile/owned-keys` and refreshes the local cache. */
    suspend fun refreshLibrary(): Result<ItchLibraryRefresh> = withContext(Dispatchers.IO) {
        val account = auth.accountOrNull()
        val token = account?.accessToken
        if (account == null || token.isNullOrBlank()) {
            return@withContext Result.failure(ItchNotAuthenticatedException())
        }
        runCatching {
            // A cache created by another itch.io account must not leak into this one.
            val knownAccounts = dao.getKnownAccountIds()
            if (knownAccounts.any { it != account.userId }) {
                dao.deleteAllNonInstalledGames()
                dao.detachAccountFromInstalledGames()
            }

            val previouslyOwned = dao.getOwnedGameIds().toSet()
            var pages = 0
            val keys = api.fetchAllOwnedKeys(token, perPage = ItchApiClient.DEFAULT_PER_PAGE) { pages += 1 }

            // A user can own several keys for the same game (purchase + bundle).
            val games = LinkedHashMap<Long, ItchGame>()
            keys.sortedByDescending { it.createdAt }
                .forEach { key ->
                    val summary = key.game ?: return@forEach
                    if (summary.id <= 0) return@forEach
                    if (games.containsKey(summary.id)) return@forEach
                    games[summary.id] = ItchGame(
                        gameId = summary.id,
                        title = summary.title,
                        url = summary.url,
                        coverUrl = summary.coverUrl,
                        author = summary.user?.label.orEmpty(),
                        authorUrl = summary.user?.url.orEmpty(),
                        isOwned = true,
                        accountId = account.userId,
                        addedAtMs = System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }

            dao.upsertPreservingInstallState(games.values.toList())
            games.values.forEach { game ->
                ItchNameCache.put(game.gameId, game.title)
                ItchGameCache.put(game)
            }

            val stale = previouslyOwned - games.keys
            if (stale.isNotEmpty()) {
                dao.clearOwnership(stale.toList())
                dao.deleteNonInstalledNotOwned()
            }

            ItchLibraryRefresh(
                ownedGames = games.size,
                pagesFetched = pages,
                accountLabel = account.label,
            )
        }
    }

    suspend fun loadUploads(gameId: Long): Result<List<ItchUpload>> = withContext(Dispatchers.IO) {
        val token = auth.tokenOrNull()
        if (token.isNullOrBlank()) return@withContext Result.failure(ItchNotAuthenticatedException())
        runCatching { api.fetchUploads(token, gameId) }
    }

    suspend fun chooseUpload(gameId: Long): Result<ItchUploadSelection> = withContext(Dispatchers.IO) {
        loadUploads(gameId).map { uploads ->
            ItchUploadSelection(
                gameId = gameId,
                uploads = uploads,
                choice = ItchUploadSelector.choose(uploads),
            )
        }
    }

    suspend fun selectUpload(gameId: Long, candidate: ItchUploadCandidate): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                dao.updateSelectedUpload(
                    gameId = gameId,
                    uploadId = candidate.id,
                    uploadName = candidate.title,
                    now = System.currentTimeMillis(),
                )
            }
        }

    /** Remembers the upload a download was started with, so updates stay on it. */
    suspend fun recordSelectedUpload(gameId: Long, upload: ItchUpload) = withContext(Dispatchers.IO) {
        dao.updateSelectedUpload(
            gameId = gameId,
            uploadId = upload.id,
            uploadName = ItchUploadSelector.toCandidate(upload).title,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * Compares the installed revision with the current upload list of the *same*
     * project and persists the verdict for the card. "Up to date" is only stored
     * when the API gives trustworthy butler build evidence.
     */
    suspend fun checkForUpdate(gameId: Long): Result<ItchUpdateDecision> = withContext(Dispatchers.IO) {
        val game = dao.getByGameId(gameId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown itch.io game $gameId"))
        val revision = game.installedRevisionOrNull()
        if (revision == null) {
            dao.updateUpdateStatus(
                gameId = gameId,
                status = ItchGame.UPDATE_STATUS_NOT_INSTALLED,
                checkedAt = System.currentTimeMillis(),
                message = null,
            )
            return@withContext Result.success(
                ItchUpdateChecker.evaluate(null, emptyList()),
            )
        }
        val token = auth.tokenOrNull()
        if (token.isNullOrBlank()) return@withContext Result.failure(ItchNotAuthenticatedException())
        runCatching {
            val uploads = api.fetchUploads(token, gameId)
            val decision = ItchUpdateChecker.evaluate(revision, uploads)
            dao.updateUpdateStatus(
                gameId = gameId,
                status = decision.status.name,
                checkedAt = System.currentTimeMillis(),
                message = decision.reason?.name,
            )
            decision
        }
    }

    /** Marks a completed ZIP install (versioned, Gameplay-managed). */
    suspend fun markZipInstalled(
        gameId: Long,
        upload: ItchUpload,
        installRoot: File,
        launchTarget: String,
        launchArgs: List<String>,
        sizeOnDisk: Long,
    ) = withContext(Dispatchers.IO) {
        markInstalled(
            gameId = gameId,
            kind = ItchInstallerKind.ZIP,
            revision = ItchUpdateChecker.revisionFor(ItchInstallerKind.ZIP, upload),
            installRoot = installRoot,
            launchTarget = launchTarget,
            launchArgs = launchArgs,
            sizeOnDisk = sizeOnDisk,
        )
    }

    /**
     * Marks a completed EXE/MSI install. The pending revision recorded when the
     * installer was prepared is used so updates keep the same upload identity.
     */
    suspend fun markInstallerInstalled(
        gameId: Long,
        revision: ItchInstalledRevision?,
        installRoot: File,
        launchTarget: String,
        launchArgs: List<String> = emptyList(),
        sizeOnDisk: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        markInstalled(
            gameId = gameId,
            kind = ItchInstallerKind.EXE,
            revision = revision ?: ItchInstalledRevision(
                kind = ItchInstallerKind.EXE,
                uploadId = null,
                buildId = null,
                userVersion = null,
                fileUpdatedAt = null,
                fileSize = 0L,
                filename = "",
            ),
            installRoot = installRoot,
            launchTarget = launchTarget,
            launchArgs = launchArgs,
            sizeOnDisk = sizeOnDisk,
        )
    }

    private suspend fun markInstalled(
        gameId: Long,
        kind: ItchInstallerKind,
        revision: ItchInstalledRevision,
        installRoot: File,
        launchTarget: String,
        launchArgs: List<String>,
        sizeOnDisk: Long,
    ) {
        val now = System.currentTimeMillis()
        val updateStatus = when {
            revision.buildId != null -> ItchGame.UPDATE_STATUS_UP_TO_DATE
            else -> ItchGame.UPDATE_STATUS_UNKNOWN
        }
        dao.markInstalled(
            gameId = gameId,
            installKind = if (kind == ItchInstallerKind.EXE) {
                ItchGame.INSTALL_KIND_EXE
            } else {
                ItchGame.INSTALL_KIND_ZIP
            },
            installRoot = installRoot.absolutePath,
            launchTarget = launchTarget,
            launchArgs = ItchArgCodec.encode(launchArgs),
            uploadId = revision.uploadId,
            uploadName = revision.filename,
            buildId = revision.buildId,
            userVersion = revision.userVersion,
            fileUpdatedAt = revision.fileUpdatedAt,
            fileSize = revision.fileSize,
            installedAtMs = now,
            sizeOnDisk = sizeOnDisk,
            updateStatus = updateStatus,
            checkedAt = now,
            updateMessage = null,
        )
        dao.getByGameId(gameId)?.let { refreshed ->
            ItchGameCache.put(refreshed)
            ItchNameCache.put(refreshed.gameId, refreshed.title)
        }
    }

    suspend fun markUninstalled(gameId: Long) = withContext(Dispatchers.IO) {
        dao.markUninstalled(gameId)
        dao.getByGameId(gameId)?.let { refreshed -> ItchGameCache.put(refreshed) }
    }

    suspend fun updateLaunchTarget(gameId: Long, target: String, args: List<String>) =
        withContext(Dispatchers.IO) {
            dao.updateLaunchTarget(gameId, target, ItchArgCodec.encode(args), System.currentTimeMillis())
        }

    suspend fun updateSizeOnDisk(gameId: Long, size: Long) = withContext(Dispatchers.IO) {
        dao.updateSizeOnDisk(gameId, size)
    }

    suspend fun recordError(gameId: Long, message: String?) = withContext(Dispatchers.IO) {
        dao.updateLastError(gameId, message, System.currentTimeMillis())
    }

    /**
     * Signs out and removes account-derived data. Installed games stay in the
     * library and remain launchable offline; only ownership/account fields go.
     */
    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        val result = auth.signOut()
        dao.deleteAllNonInstalledGames()
        dao.detachAccountFromInstalledGames()
        dao.getAllAsList().forEach { game ->
            ItchGameCache.put(game)
            // Installed games keep resolving their name for launch diagnostics.
            ItchNameCache.put(game.gameId, game.title)
        }
        result
    }
}

