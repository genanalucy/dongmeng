package com.verba.interpretation.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "history_sessions", indices = [Index(value = ["userId", "createdAtMillis"])])
data class HistorySessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val userId: String,
    val kind: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "history_turns", indices = [Index(value = ["sessionId", "completedAtMillis"]), Index(value = ["userId"])])
data class HistoryTurnEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val userId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceTextEncrypted: String,
    val translatedTextEncrypted: String,
    val completedAtMillis: Long,
)

@Entity(tableName = "history_tombstones", indices = [Index(value = ["userId", "createdAtMillis"])])
data class HistoryTombstoneEntity(
    @androidx.room.PrimaryKey val id: String,
    val userId: String,
    val entityType: String,
    val entityId: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "history_outbox", indices = [Index(value = ["userId", "priority", "createdAtMillis"])])
data class HistoryOutboxEntity(
    @androidx.room.PrimaryKey val id: String,
    val userId: String,
    val operation: String,
    val entityType: String,
    val entityId: String,
    val payloadEncrypted: String,
    val priority: Int,
    val createdAtMillis: Long,
)

@Entity(tableName = "history_cursors")
data class HistoryCursorEntity(
    @androidx.room.PrimaryKey val userId: String,
    val cursorEncrypted: String,
    val updatedAtMillis: Long,
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: HistorySessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurn(turn: HistoryTurnEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTombstone(tombstone: HistoryTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(operation: HistoryOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: HistoryCursorEntity)

    @Query("SELECT * FROM history_outbox WHERE userId = :userId ORDER BY priority DESC, createdAtMillis ASC LIMIT :limit")
    suspend fun pendingOutbox(userId: String, limit: Int): List<HistoryOutboxEntity>

    @Query("DELETE FROM history_outbox WHERE id IN (:ids)")
    suspend fun deleteOutbox(ids: List<String>)

    @Query("DELETE FROM history_sessions WHERE userId = :userId")
    suspend fun deleteSessions(userId: String)

    @Query("DELETE FROM history_sessions WHERE userId = :userId AND id = :sessionId")
    suspend fun deleteSession(userId: String, sessionId: String)

    @Query("DELETE FROM history_turns WHERE userId = :userId AND sessionId = :sessionId")
    suspend fun deleteTurnsForSession(userId: String, sessionId: String)

    @Query("DELETE FROM history_turns WHERE userId = :userId")
    suspend fun deleteTurns(userId: String)

    @Query("DELETE FROM history_tombstones WHERE userId = :userId")
    suspend fun deleteTombstones(userId: String)

    @Query("DELETE FROM history_outbox WHERE userId = :userId")
    suspend fun deleteOutboxForUser(userId: String)

    @Query("DELETE FROM history_cursors WHERE userId = :userId")
    suspend fun deleteCursor(userId: String)

    @Transaction
    suspend fun clearUser(userId: String) {
        deleteSessions(userId)
        deleteTurns(userId)
        deleteTombstones(userId)
        deleteOutboxForUser(userId)
        deleteCursor(userId)
    }
}

@Database(
    entities = [HistorySessionEntity::class, HistoryTurnEntity::class, HistoryTombstoneEntity::class, HistoryOutboxEntity::class, HistoryCursorEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        fun create(context: Context): HistoryDatabase = Room.databaseBuilder(
            context.applicationContext,
            HistoryDatabase::class.java,
            "encrypted_local_history.db",
        ).build()
    }
}
