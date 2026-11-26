package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gamenative.data.ItchGame
import kotlinx.coroutines.flow.Flow

/** Room access for the itch.io library cache and install metadata. */
@Dao
interface ItchGameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(games: List<ItchGame>)

    @Query("SELECT * FROM itch_games ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ItchGame>>

    @Query("SELECT * FROM itch_games ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAllAsList(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE game_id = :gameId")
    suspend fun getByGameId(gameId: Long): ItchGame?

    @Query("SELECT * FROM itch_games WHERE is_installed = 1 ORDER BY title COLLATE NOCASE ASC")
    suspend fun getInstalledGames(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE is_installed = 0")
    suspend fun getNonInstalledGames(): List<ItchGame>

    @Query("SELECT * FROM itch_games WHERE is_owned = 1")
    suspend fun getOwnedGames(): List<ItchGame>

    @Query("SELECT game_id FROM itch_games WHERE is_owned = 1")
    suspend fun getOwnedGameIds(): List<Long>

    @Query("SELECT COUNT(*) FROM itch_games")
    fun getCount(): Flow<Int>

    /**
     * Refreshes account-derived metadata while keeping everything that describes
     * the local installation (upload choice, revision, launch target, size).
     */
    @Transaction
    suspend fun upsertPreservingInstallState(games: List<ItchGame>) {
        if (games.isEmpty()) return
        val existing = getGamesByGameIds(games.map { it.gameId }).associateBy { it.gameId }
        val merged = games.map { incoming ->
            val local = existing[incoming.gameId] ?: return@map incoming
            incoming.copy(
                isInstalled = local.isInstalled,
                installKind = local.installKind,
                installRoot = local.installRoot,
                launchTarget = local.launchTarget,
                launchArgs = local.launchArgs,
                selectedUploadId = local.selectedUploadId,
                selectedUploadName = local.selectedUploadName,
                installedUploadId = local.installedUploadId,
                installedUploadName = local.installedUploadName,
                installedBuildId = local.installedBuildId,
                installedUserVersion = local.installedUserVersion,
                installedFileUpdatedAt = local.installedFileUpdatedAt,
                installedFileSize = local.installedFileSize,
                installedAtMs = local.installedAtMs,
                updateStatus = local.updateStatus,
                updateCheckedAtMs = local.updateCheckedAtMs,
                updateMessage = local.updateMessage,
                sizeOnDisk = local.sizeOnDisk,
                lastError = local.lastError,
                addedAtMs = local.addedAtMs,
            )
        }
        insertAll(merged)
    }

    @Query("SELECT * FROM itch_games WHERE game_id IN (:gameIds)")
    suspend fun getGamesByGameIds(gameIds: List<Long>): List<ItchGame>

    @Query(
        "UPDATE itch_games SET is_installed = 1, install_kind = :installKind, install_root = :installRoot, " +
            "launch_target = :launchTarget, launch_args = :launchArgs, installed_upload_id = :uploadId, " +
            "installed_upload_name = :uploadName, installed_build_id = :buildId, " +
            "installed_user_version = :userVersion, installed_file_updated_at = :fileUpdatedAt, " +
            "installed_file_size = :fileSize, installed_at_ms = :installedAtMs, size_on_disk = :sizeOnDisk, " +
            "update_status = :updateStatus, update_checked_at_ms = :checkedAt, update_message = :updateMessage, " +
            "last_error = NULL, updated_at_ms = :installedAtMs WHERE game_id = :gameId",
    )
    suspend fun markInstalled(
        gameId: Long,
        installKind: String,
        installRoot: String,
        launchTarget: String,
        launchArgs: String,
        uploadId: Long?,
        uploadName: String,
        buildId: Long?,
        userVersion: String?,
        fileUpdatedAt: String?,
        fileSize: Long,
        installedAtMs: Long,
        sizeOnDisk: Long,
        updateStatus: String,
        checkedAt: Long,
        updateMessage: String?,
    )

    @Query(
        "UPDATE itch_games SET is_installed = 0, install_kind = 'NONE', install_root = '', launch_target = '', " +
            "launch_args = '', installed_upload_id = NULL, installed_upload_name = '', installed_build_id = NULL, " +
            "installed_user_version = NULL, installed_file_updated_at = NULL, installed_file_size = 0, " +
            "installed_at_ms = 0, size_on_disk = 0, update_status = 'NOT_INSTALLED', update_message = NULL " +
            "WHERE game_id = :gameId",
    )
    suspend fun markUninstalled(gameId: Long)

    @Query("UPDATE itch_games SET selected_upload_id = :uploadId, selected_upload_name = :uploadName, updated_at_ms = :now WHERE game_id = :gameId")
    suspend fun updateSelectedUpload(gameId: Long, uploadId: Long?, uploadName: String, now: Long)

    @Query("UPDATE itch_games SET launch_target = :target, launch_args = :args, updated_at_ms = :now WHERE game_id = :gameId")
    suspend fun updateLaunchTarget(gameId: Long, target: String, args: String, now: Long)

    @Query("UPDATE itch_games SET update_status = :status, update_checked_at_ms = :checkedAt, update_message = :message WHERE game_id = :gameId")
    suspend fun updateUpdateStatus(gameId: Long, status: String, checkedAt: Long, message: String?)

    @Query("UPDATE itch_games SET last_error = :error, updated_at_ms = :now WHERE game_id = :gameId")
    suspend fun updateLastError(gameId: Long, error: String?, now: Long)

    @Query("UPDATE itch_games SET size_on_disk = :size WHERE game_id = :gameId")
    suspend fun updateSizeOnDisk(gameId: Long, size: Long)

    @Query("UPDATE itch_games SET is_owned = 0, account_id = NULL WHERE game_id IN (:gameIds)")
    suspend fun clearOwnership(gameIds: List<Long>)

    @Query("SELECT DISTINCT account_id FROM itch_games WHERE account_id IS NOT NULL")
    suspend fun getKnownAccountIds(): List<Long>

    @Query("DELETE FROM itch_games WHERE is_installed = 0 AND is_owned = 0")
    suspend fun deleteNonInstalledNotOwned()

    /** Account-derived rows disappear; installed games remain launchable. */
    @Query("DELETE FROM itch_games WHERE is_installed = 0")
    suspend fun deleteAllNonInstalledGames()

    @Query("UPDATE itch_games SET account_id = NULL, is_owned = 0 WHERE is_installed = 1")
    suspend fun detachAccountFromInstalledGames()

    @Query("DELETE FROM itch_games")
    suspend fun deleteAll()
}
