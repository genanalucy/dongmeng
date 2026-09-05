package com.verba.interpretation.history

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.UUID
import org.json.JSONObject

private const val DELETE_PRIORITY = 100
private const val UPSERT_PRIORITY = 10
private const val USER_ID_KEY = "history_user_id"

/** Narrow boundary for Cloud's future history sync contract; no endpoint is assumed here. */
interface LocalHistoryTransport {
    suspend fun upload(userId: String, operations: List<HistorySyncOperation>): HistoryUploadResult
}

data class HistorySyncOperation(
    val id: String,
    val operation: String,
    val entityType: String,
    val entityId: String,
    val payload: String,
)

sealed interface HistoryUploadResult {
    data class Accepted(val operationIds: Set<String>) : HistoryUploadResult
    data object RetryableFailure : HistoryUploadResult
}

object NoopLocalHistoryTransport : LocalHistoryTransport {
    override suspend fun upload(userId: String, operations: List<HistorySyncOperation>): HistoryUploadResult = HistoryUploadResult.RetryableFailure
}

interface HistorySyncScheduler {
    fun schedule(userId: String)
    fun cancel(userId: String)
}

class WorkManagerHistorySyncScheduler(private val workManager: WorkManager) : HistorySyncScheduler {
    override fun schedule(userId: String) {
        val request = OneTimeWorkRequestBuilder<LocalHistorySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(androidx.work.workDataOf(USER_ID_KEY to userId))
            .build()
        workManager.enqueueUniqueWork(workName(userId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(userId: String) {
        workManager.cancelUniqueWork(workName(userId))
    }

    private fun workName(userId: String) = "local-history-sync-$userId"
}

data class CompletedTurn(
    val userId: String,
    val localSessionId: String?,
    val mode: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceText: String,
    val translatedText: String,
    val completedAtMillis: Long,
)

class LocalHistoryRepository(
    private val database: HistoryDatabase,
    private val cipher: HistoryPayloadCipher,
    private val syncScheduler: HistorySyncScheduler,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    /** Returns the durable local session id, allocating it only for a valid completed turn. */
    suspend fun recordCompletedTurn(turn: CompletedTurn): String {
        require(turn.userId.isNotBlank())
        require(turn.sourceText.isNotBlank())
        require(turn.translatedText.isNotBlank())
        val sessionId = turn.localSessionId ?: ids()
        val turnId = ids()
        val session = HistorySessionEntity(sessionId, turn.userId, turn.mode, turn.completedAtMillis)
        val savedTurn = HistoryTurnEntity(
            id = turnId,
            sessionId = sessionId,
            userId = turn.userId,
            sourceLanguage = turn.sourceLanguage,
            targetLanguage = turn.targetLanguage,
            sourceTextEncrypted = cipher.encrypt(turn.sourceText),
            translatedTextEncrypted = cipher.encrypt(turn.translatedText),
            completedAtMillis = turn.completedAtMillis,
        )
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("turnId", turnId)
            .put("mode", turn.mode)
            .put("sourceLanguage", turn.sourceLanguage)
            .put("targetLanguage", turn.targetLanguage)
            .put("sourceText", turn.sourceText)
            .put("translatedText", turn.translatedText)
            .put("completedAtMillis", turn.completedAtMillis)
            .toString()
        database.withTransaction {
            database.historyDao().insertSession(session)
            database.historyDao().insertTurn(savedTurn)
            database.historyDao().insertOutbox(
                HistoryOutboxEntity(ids(), turn.userId, "UPSERT", "turn", turnId, cipher.encrypt(payload), UPSERT_PRIORITY, turn.completedAtMillis),
            )
        }
        syncScheduler.schedule(turn.userId)
        return sessionId
    }

    suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long) {
        val tombstoneId = ids()
        val payload = JSONObject().put("sessionId", sessionId).put("deletedAtMillis", deletedAtMillis).toString()
        database.withTransaction {
            database.historyDao().deleteTurnsForSession(userId, sessionId)
            database.historyDao().deleteSession(userId, sessionId)
            database.historyDao().insertTombstone(HistoryTombstoneEntity(tombstoneId, userId, "session", sessionId, deletedAtMillis))
            database.historyDao().insertOutbox(
                HistoryOutboxEntity(ids(), userId, "DELETE", "session", sessionId, cipher.encrypt(payload), DELETE_PRIORITY, deletedAtMillis),
            )
        }
        syncScheduler.schedule(userId)
    }

    suspend fun discardUser(userId: String) {
        database.withTransaction { database.historyDao().clearUser(userId) }
        syncScheduler.cancel(userId)
    }

    suspend fun sync(userId: String, transport: LocalHistoryTransport): Boolean {
        val pending = database.historyDao().pendingOutbox(userId, limit = 100)
        if (pending.isEmpty()) return true
        val operations = pending.map {
            HistorySyncOperation(it.id, it.operation, it.entityType, it.entityId, cipher.decrypt(it.payloadEncrypted))
        }
        return when (val result = transport.upload(userId, operations)) {
            is HistoryUploadResult.Accepted -> {
                database.historyDao().deleteOutbox(result.operationIds.toList())
                true
            }
            HistoryUploadResult.RetryableFailure -> false
        }
    }

    companion object {
        fun create(context: Context): LocalHistoryRepository = LocalHistoryRepository(
            database = HistoryDatabase.create(context),
            cipher = AndroidKeystoreHistoryPayloadCipher(),
            syncScheduler = WorkManagerHistorySyncScheduler(WorkManager.getInstance(context.applicationContext)),
        )
    }
}

class LocalHistorySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(USER_ID_KEY) ?: return Result.failure()
        return if (LocalHistoryRepository.create(applicationContext).sync(userId, NoopLocalHistoryTransport)) Result.success() else Result.retry()
    }
}
