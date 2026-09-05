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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history_sessions", indices = [Index(value = ["userId", "createdAtMillis"])])
data class HistorySessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val userId: String,
    val kind: String,
    val createdAtMillis: Long,
    val titleEncrypted: String? = null,
    val titleUpdatedAtMillis: Long? = null,
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

/** Permanent local delete marker. Its existence always wins over pulled content. */
@Entity(tableName = "history_tombstones", indices = [Index(value = ["userId", "createdAtMillis"]), Index(value = ["userId", "entityId"], unique = true)])
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
    val sessionId: String? = null,
    val turnId: String? = null,
)

@Entity(tableName = "history_cursors")
data class HistoryCursorEntity(
    @androidx.room.PrimaryKey val userId: String,
    val cursorEncrypted: String,
    val updatedAtMillis: Long,
)

@Entity(tableName = "history_sync_state")
data class HistorySyncStateEntity(
    @androidx.room.PrimaryKey val userId: String,
    val quotaExceeded: Boolean = false,
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(session: HistorySessionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(session: HistorySessionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTurn(turn: HistoryTurnEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTombstone(tombstone: HistoryTombstoneEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertOutbox(operation: HistoryOutboxEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCursor(cursor: HistoryCursorEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSyncState(state: HistorySyncStateEntity)

    @Query("SELECT * FROM history_outbox WHERE userId = :userId ORDER BY priority DESC, createdAtMillis ASC LIMIT :limit")
    suspend fun pendingOutbox(userId: String, limit: Int): List<HistoryOutboxEntity>
    @Query("SELECT * FROM history_sessions WHERE userId = :userId ORDER BY createdAtMillis DESC") fun observeSessions(userId: String): Flow<List<HistorySessionEntity>>
    @Query("SELECT * FROM history_turns WHERE userId = :userId ORDER BY completedAtMillis ASC") fun observeTurns(userId: String): Flow<List<HistoryTurnEntity>>
    @Query("SELECT * FROM history_sessions WHERE userId = :userId ORDER BY createdAtMillis DESC") suspend fun sessions(userId: String): List<HistorySessionEntity>
    @Query("SELECT * FROM history_turns WHERE userId = :userId ORDER BY completedAtMillis ASC") suspend fun turns(userId: String): List<HistoryTurnEntity>
    @Query("SELECT EXISTS(SELECT 1 FROM history_tombstones WHERE userId = :userId AND entityId = :sessionId)") suspend fun hasSessionTombstone(userId: String, sessionId: String): Boolean
    @Query("SELECT * FROM history_cursors WHERE userId = :userId") suspend fun cursor(userId: String): HistoryCursorEntity?
    @Query("SELECT quotaExceeded FROM history_sync_state WHERE userId = :userId") suspend fun quotaExceeded(userId: String): Boolean?
    @Query("DELETE FROM history_outbox WHERE id IN (:ids)") suspend fun deleteOutbox(ids: List<String>)
    @Query("DELETE FROM history_outbox WHERE userId = :userId AND sessionId = :sessionId AND operation != 'DELETE'") suspend fun deleteStaleSessionOutbox(userId: String, sessionId: String)
    @Query("DELETE FROM history_sessions WHERE userId = :userId") suspend fun deleteSessions(userId: String)
    @Query("DELETE FROM history_sessions WHERE userId = :userId AND id = :sessionId") suspend fun deleteSession(userId: String, sessionId: String)
    @Query("DELETE FROM history_turns WHERE userId = :userId AND sessionId = :sessionId") suspend fun deleteTurnsForSession(userId: String, sessionId: String)
    @Query("DELETE FROM history_turns WHERE userId = :userId") suspend fun deleteTurns(userId: String)
    @Query("DELETE FROM history_tombstones WHERE userId = :userId") suspend fun deleteTombstones(userId: String)
    @Query("DELETE FROM history_outbox WHERE userId = :userId") suspend fun deleteOutboxForUser(userId: String)
    @Query("DELETE FROM history_cursors WHERE userId = :userId") suspend fun deleteCursor(userId: String)
    @Query("DELETE FROM history_sync_state WHERE userId = :userId") suspend fun deleteSyncState(userId: String)

    @Transaction suspend fun clearUser(userId: String) {
        deleteSessions(userId); deleteTurns(userId); deleteTombstones(userId); deleteOutboxForUser(userId); deleteCursor(userId); deleteSyncState(userId)
    }
}

@Database(entities = [HistorySessionEntity::class, HistoryTurnEntity::class, HistoryTombstoneEntity::class, HistoryOutboxEntity::class, HistoryCursorEntity::class, HistorySyncStateEntity::class], version = 2, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history_sessions ADD COLUMN titleEncrypted TEXT")
                db.execSQL("ALTER TABLE history_sessions ADD COLUMN titleUpdatedAtMillis INTEGER")
                db.execSQL("ALTER TABLE history_outbox ADD COLUMN sessionId TEXT")
                db.execSQL("ALTER TABLE history_outbox ADD COLUMN turnId TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS history_sync_state (userId TEXT NOT NULL PRIMARY KEY, quotaExceeded INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_history_tombstones_userId_entityId ON history_tombstones(userId, entityId)")
            }
        }
        fun create(context: Context): HistoryDatabase = Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "encrypted_local_history.db").addMigrations(MIGRATION_1_2).build()
    }
}
