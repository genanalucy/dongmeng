package com.verba.interpretation.history

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.HistoryApi
import com.verba.interpretation.cloud.HistoryPushOperation
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private const val DELETE_PRIORITY = 100
private const val UPSERT_PRIORITY = 10
private const val USER_ID_KEY = "history_user_id"
private const val OUTBOX_BATCH = 100

enum class HistoryOperationKind(val cloudKind: String) { TURN_UPSERT("turn.upsert"), SESSION_DELETE("session.delete"), TITLE_PATCH("title.patch") }

/** Cloud payload is TLS plaintext/base64; database payloads remain Keystore-encrypted. */
interface LocalHistoryTransport {
    suspend fun push(operations: List<HistoryPushOperation>)
    suspend fun pull(cursor: Long): com.verba.interpretation.cloud.HistoryPullResponse
}

class CloudHistoryTransport(private val api: HistoryApi) : LocalHistoryTransport {
    override suspend fun push(operations: List<HistoryPushOperation>) { api.pushHistory(operations) }
    override suspend fun pull(cursor: Long) = api.pullHistory(cursor)
}

interface HistorySyncScheduler { fun schedule(userId: String); fun cancel(userId: String) }
class WorkManagerHistorySyncScheduler(private val workManager: WorkManager) : HistorySyncScheduler {
    override fun schedule(userId: String) {
        val request = OneTimeWorkRequestBuilder<LocalHistorySyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setInputData(androidx.work.workDataOf(USER_ID_KEY to userId)).build()
        workManager.enqueueUniqueWork(workName(userId), ExistingWorkPolicy.KEEP, request)
    }
    override fun cancel(userId: String) { workManager.cancelUniqueWork(workName(userId)) }
    private fun workName(userId: String) = "local-history-sync-$userId"
}

data class CompletedTurn(val userId: String, val localSessionId: String?, val mode: String, val sourceLanguage: String, val targetLanguage: String, val sourceText: String, val translatedText: String, val completedAtMillis: Long)
data class HistoryTurn(val id: String, val sourceLanguage: String, val targetLanguage: String, val sourceText: String, val translatedText: String, val completedAtMillis: Long)
data class HistorySession(val id: String, val kind: String, val title: String?, val createdAtMillis: Long, val turns: List<HistoryTurn>)

class LocalHistoryRepository(private val database: HistoryDatabase, private val cipher: HistoryPayloadCipher, private val syncScheduler: HistorySyncScheduler, private val ids: () -> String = { UUID.randomUUID().toString() }) {
    private val dao get() = database.historyDao()

    suspend fun recordCompletedTurn(turn: CompletedTurn): String {
        require(turn.userId.isNotBlank() && turn.sourceText.isNotBlank() && turn.translatedText.isNotBlank())
        check(!quotaExceeded(turn.userId)) { "history_limit_exceeded" }
        val sessionId = turn.localSessionId ?: ids(); val turnId = ids()
        val wirePayload = JSONObject().put("source_language", turn.sourceLanguage).put("target_language", turn.targetLanguage).put("source_text", turn.sourceText).put("translated_text", turn.translatedText).put("completed_at_millis", turn.completedAtMillis).toString()
        database.withTransaction {
            dao.insertSession(HistorySessionEntity(sessionId, turn.userId, turn.mode, turn.completedAtMillis))
            dao.insertTurn(HistoryTurnEntity(turnId, sessionId, turn.userId, turn.sourceLanguage, turn.targetLanguage, cipher.encrypt(turn.sourceText), cipher.encrypt(turn.translatedText), turn.completedAtMillis))
            dao.insertOutbox(HistoryOutboxEntity(ids(), turn.userId, HistoryOperationKind.TURN_UPSERT.name, "turn", turnId, cipher.encrypt(wirePayload), UPSERT_PRIORITY, turn.completedAtMillis, sessionId, turnId))
        }
        syncScheduler.schedule(turn.userId); return sessionId
    }

    suspend fun renameSession(userId: String, sessionId: String, title: String, updatedAtMillis: Long) {
        val normalized = title.trim(); require(normalized.isNotEmpty())
        database.withTransaction {
            val current = dao.sessions(userId).firstOrNull { it.id == sessionId } ?: return@withTransaction
            if (dao.hasSessionTombstone(userId, sessionId)) return@withTransaction
            dao.upsertSession(current.copy(titleEncrypted = cipher.encrypt(normalized), titleUpdatedAtMillis = updatedAtMillis))
            dao.insertOutbox(HistoryOutboxEntity(ids(), userId, HistoryOperationKind.TITLE_PATCH.name, "session", sessionId, cipher.encrypt(normalized), UPSERT_PRIORITY, updatedAtMillis, sessionId))
        }; syncScheduler.schedule(userId)
    }

    suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long) {
        database.withTransaction {
            dao.deleteTurnsForSession(userId, sessionId); dao.deleteSession(userId, sessionId)
            dao.insertTombstone(HistoryTombstoneEntity(ids(), userId, "session", sessionId, deletedAtMillis))
            dao.deleteStaleSessionOutbox(userId, sessionId)
            dao.insertOutbox(HistoryOutboxEntity(ids(), userId, "DELETE", "session", sessionId, cipher.encrypt(""), DELETE_PRIORITY, deletedAtMillis, sessionId))
        }; syncScheduler.schedule(userId)
    }

    suspend fun clearAll(userId: String, nowMillis: Long) = dao.sessions(userId).forEach { deleteSession(userId, it.id, nowMillis) }
    suspend fun discardUser(userId: String) { database.withTransaction { dao.clearUser(userId) }; syncScheduler.cancel(userId) }
    suspend fun quotaExceeded(userId: String): Boolean = dao.quotaExceeded(userId) ?: false

    fun observeHistory(userId: String): Flow<List<HistorySession>> = combine(dao.observeSessions(userId), dao.observeTurns(userId)) { sessions, turns ->
        sessions.map { session -> HistorySession(session.id, session.kind, session.titleEncrypted?.let(cipher::decrypt), session.createdAtMillis, turns.filter { it.sessionId == session.id }.map { turn -> HistoryTurn(turn.id, turn.sourceLanguage, turn.targetLanguage, cipher.decrypt(turn.sourceTextEncrypted), cipher.decrypt(turn.translatedTextEncrypted), turn.completedAtMillis) }) }
    }

    /** Push is always followed by pull; pull also runs with an empty outbox. */
    suspend fun sync(userId: String, transport: LocalHistoryTransport): Boolean = try {
        val pending = dao.pendingOutbox(userId, OUTBOX_BATCH)
        if (pending.isNotEmpty()) {
            val request = pending.map { operation -> operation.toCloudOperation(cipher) }
            transport.push(request)
            dao.deleteOutbox(pending.map { it.id })
        }
        do {
            val currentCursor = dao.cursor(userId)?.let { cipher.decrypt(it.cursorEncrypted).toLong() } ?: 0L
            val page = transport.pull(currentCursor)
            database.withTransaction { applyPage(userId, page) }
        } while (page.hasMore)
        true
    } catch (error: CloudApiException) {
        if (error.statusCode == 409 && error.message?.contains("history_limit_exceeded") == true) dao.upsertSyncState(HistorySyncStateEntity(userId, true))
        false
    } catch (_: Exception) { false }

    private suspend fun applyPage(userId: String, page: com.verba.interpretation.cloud.HistoryPullResponse) {
        page.changes.forEach { change ->
            val session = change.session
            if (session.deletedAtMillis != null) {
                dao.deleteTurnsForSession(userId, session.id); dao.deleteSession(userId, session.id)
                dao.insertTombstone(HistoryTombstoneEntity("remote-${session.id}", userId, "session", session.id, session.deletedAtMillis))
            } else if (!dao.hasSessionTombstone(userId, session.id)) {
                val local = dao.sessions(userId).firstOrNull { it.id == session.id }
                val localTitleAt = local?.titleUpdatedAtMillis ?: Long.MIN_VALUE
                val remoteTitleAt = session.titleUpdatedAtMillis ?: Long.MIN_VALUE
                dao.upsertSession(HistorySessionEntity(session.id, userId, local?.kind ?: "synced", local?.createdAtMillis ?: session.createdAtMillis, if (remoteTitleAt >= localTitleAt) session.title?.let(cipher::encrypt) ?: local?.titleEncrypted else local?.titleEncrypted, maxOf(localTitleAt, remoteTitleAt).takeIf { it != Long.MIN_VALUE }))
                change.turn?.takeIf { it.deletedAtMillis == null && it.payloadBase64 != null }?.let { remote ->
                    val payload = JSONObject(String(Base64.decode(remote.payloadBase64, Base64.DEFAULT), UTF_8))
                    dao.insertTurn(HistoryTurnEntity(remote.id, session.id, userId, payload.required("source_language"), payload.required("target_language"), cipher.encrypt(payload.required("source_text")), cipher.encrypt(payload.required("translated_text")), payload.optLong("completed_at_millis", remote.createdAtMillis)))
                }
            }
        }
        dao.upsertCursor(HistoryCursorEntity(userId, cipher.encrypt(page.nextCursor.toString()), System.currentTimeMillis()))
    }

    private fun HistoryOutboxEntity.toCloudOperation(cipher: HistoryPayloadCipher): HistoryPushOperation {
        val kind = when (operation) { "DELETE" -> HistoryOperationKind.SESSION_DELETE; else -> HistoryOperationKind.valueOf(operation) }
        return HistoryPushOperation(id, kind.cloudKind, requireNotNull(sessionId), turnId, if (kind == HistoryOperationKind.SESSION_DELETE) null else Base64.encodeToString(cipher.decrypt(payloadEncrypted).toByteArray(UTF_8), Base64.NO_WRAP))
    }
    companion object { fun create(context: Context): LocalHistoryRepository = LocalHistoryRepository(HistoryDatabase.create(context), AndroidKeystoreHistoryPayloadCipher(), WorkManagerHistorySyncScheduler(WorkManager.getInstance(context.applicationContext))) }
}

private fun JSONObject.required(key: String): String = optString(key).trim().takeIf { it.isNotEmpty() } ?: error("Missing history $key")

class LocalHistorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(USER_ID_KEY) ?: return Result.failure()
        val api = CloudApi(CloudEndpointSettings(applicationContext), KeystoreTokenStore(applicationContext), SharedPreferencesInstallationIdStore(applicationContext))
        if (!api.hasCredentials()) return Result.success()
        val synced = LocalHistoryRepository.create(applicationContext).sync(userId, CloudHistoryTransport(api))
        return if (synced) Result.success() else Result.retry()
    }
}
