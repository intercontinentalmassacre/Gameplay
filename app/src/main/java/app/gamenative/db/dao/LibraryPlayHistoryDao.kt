package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.gamenative.data.LibraryPlayHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryPlayHistoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entry: LibraryPlayHistory)

    @Query("UPDATE library_play_history SET last_played = :startedAt WHERE app_id = :appId")
    suspend fun updateLastPlayed(appId: String, startedAt: Long)

    @Transaction
    suspend fun recordStart(appId: String, startedAt: Long) {
        insertIfMissing(LibraryPlayHistory(appId, startedAt))
        updateLastPlayed(appId, startedAt)
    }

    @Query(
        "UPDATE library_play_history SET last_session_ms = :durationMs, " +
            "total_play_ms = total_play_ms + :durationMs WHERE app_id = :appId",
    )
    suspend fun updateDuration(appId: String, durationMs: Long)

    @Transaction
    suspend fun recordCompletion(appId: String, startedAt: Long, durationMs: Long) {
        insertIfMissing(LibraryPlayHistory(appId, startedAt))
        updateDuration(appId, durationMs)
    }

    @Query("SELECT * FROM library_play_history WHERE app_id = :appId")
    fun observe(appId: String): Flow<LibraryPlayHistory?>

    @Query("SELECT * FROM library_play_history")
    fun getAll(): Flow<List<LibraryPlayHistory>>
}
