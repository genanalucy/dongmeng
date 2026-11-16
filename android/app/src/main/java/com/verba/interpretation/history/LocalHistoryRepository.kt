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
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.HistoryApi
import com.verba.interpretation.cloud.HistoryPushOperation
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import com.verba.interpretation.cloud.TokenStore
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
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
    suspend fun isAccountActive(userId: String): Boolean = true
}

enum class HistorySyncFailure {
    NO_CREDENTIALS,
    UNAUTHORIZED,
    SERVER,
    INVALID_RESPONSE,
    REMOTE_HISTORY_DATA,
    LOCAL_DATABASE,
    LOCAL_DATA,
    NETWORK,
    UNKNOWN,
}

data class HistorySyncResult(
    val success: Boolean,
    val failure: HistorySyncFailure? = null,
    /** Debug-only diagnostic; contains the exception type only, never user content or credentials. */
    val diagnosticType: String? = null,
)

class CloudHistoryTransport(private val api: HistoryApi) : LocalHistoryTransport {
    override suspend fun push(operations: List<HistoryPushOperation>) { api.pushHistory(operations) }
    override suspend fun pull(cursor: Long) = api.pullHistory(cursor)
    // An ordinary CloudApi rereads mutable login credentials on every request: fail closed.
    // Production repository.sync replaces it with forAccount(), including history-page calls.
    override suspend fun isAccountActive(userId: String): Boolean = api !is CloudApi

    companion object {
        /** IO only. Tokens are pinned; expiry fails closed without rotating the shared refresh token. */
        fun forAccount(context: Context, userId: String): LocalHistoryTransport? {
            val store = KeystoreTokenStore(context)
            val original = store.read() ?: return null
            val snapshot = object : TokenStore {
                private var tokens: AuthTokens? = original
                override fun read(): AuthTokens? = tokens
                override fun write(tokens: AuthTokens) { this.tokens = tokens }
                override fun clear() { tokens = null }
            }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/auth/refresh")) {
                    throw CloudApiException("history_credentials_expired", 401)
                }
                chain.proceed(chain.request())
            }.build()
            val api = CloudApi(CloudEndpointSettings(context), snapshot, SharedPreferencesInstallationIdStore(context), client)
            if (api.currentUser().id != userId) return null
            val ownerId = userId
            return object : LocalHistoryTransport {
                override suspend fun isAccountActive(userId: String): Boolean =
                    userId == ownerId && store.read() == original && snapshot.read() != null
                override suspend fun push(operations: List<HistoryPushOperation>) { api.pushHistory(operations) }
                override suspend fun pull(cursor: Long) = api.pullHistory(cursor)
            }
        }
    }
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

data class CompletedTurn(val userId: String, val localSessionId: String?, val mode: String, val sourceLanguage: String, val targetLanguage: String, val sourceText: String, val translatedText: String, val completedAtMillis: Long, val stableTurnId: String? = null, val accountEpoch: Long? = null)
data class HistoryTurn(val id: String, val sourceLanguage: String, val targetLanguage: String, val sourceText: String, val translatedText: String, val completedAtMillis: Long)
data class HistorySession(val id: String, val kind: String, val title: String?, val createdAtMillis: Long, val turns: List<HistoryTurn>)

class LocalHistoryRepository(private val database: HistoryDatabase, private val cipher: HistoryPayloadCipher, private val syncScheduler: HistorySyncScheduler, private val ids: () -> String = { UUID.randomUUID().toString() }) {
    private val dao get() = database.historyDao()
    private var accountContext: Context? = null

    /** Direct Room/test construction can opt in to the same durable epoch store as create(). */
    constructor(context: Context, database: HistoryDatabase, cipher: HistoryPayloadCipher, syncScheduler: HistorySyncScheduler, ids: () -> String = { UUID.randomUUID().toString() }) : this(database, cipher, syncScheduler, ids) {
        accountContext = context.applicationContext
    }

    private fun currentEpoch(userId: String): Long =
        captureAccountEpoch(checkNotNull(accountContext) { "history_account_context_required" }, userId)

    /** Success means local transaction commit, not scheduling or cloud synchronization. */
    suspend fun recordCompletedTurn(turn: CompletedTurn): String = accountMutex.withLock {
        require(turn.userId.isNotBlank() && turn.sourceText.isNotBlank() && turn.translatedText.isNotBlank())
        require(turn.stableTurnId == null || turn.stableTurnId.isNotBlank())
        val epoch = turn.accountEpoch ?: accountContext?.let { currentEpoch(turn.userId) }
        if (accountContext?.let { !isAccountActive(it, turn.userId) } == true) {
            check(epoch == currentEpoch(turn.userId)) { "history_account_revoked" }
            // Finish an interrupted discard before allowing a fresh binding to commit new content.
            database.withTransaction { dao.clearUser(turn.userId) }
            withContext(Dispatchers.IO) {
                check(requireNotNull(accountContext).getSharedPreferences(EPOCH_PREFERENCES, Context.MODE_PRIVATE)
                    .edit().putBoolean(revokedKey(turn.userId), false).commit()) { "history_account_epoch_write_failed" }
            }
        }
        val sessionId = database.withTransaction {
            if (epoch != null) check(epoch == currentEpoch(turn.userId)) { "history_account_revoked" }
            turn.localSessionId?.let {
                check(!dao.hasSessionTombstone(turn.userId, it)) { "history_session_deleted" }
            }
            turn.stableTurnId?.let {
                check(!dao.hasSessionTombstone(turn.userId, it)) { "history_turn_deleted" }
            }
            val existing = turn.stableTurnId?.let { dao.turn(it) }
            if (existing != null) {
                check(existing.userId == turn.userId && (turn.localSessionId == null || existing.sessionId == turn.localSessionId)) { "history_turn_ownership_mismatch" }
                check(!dao.hasSessionTombstone(turn.userId, existing.sessionId)) { "history_session_deleted" }
                return@withTransaction existing.sessionId
            }
            check(!quotaExceeded(turn.userId)) { "history_limit_exceeded" }
            val id = turn.localSessionId ?: ids()
            check(!dao.hasSessionTombstone(turn.userId, id)) { "history_session_deleted" }
            check(!dao.sessionBelongsToAnotherUser(id, turn.userId)) { "history_session_ownership_mismatch" }
            val turnId = turn.stableTurnId ?: ids()
            val wirePayload = JSONObject().put("source_language", turn.sourceLanguage).put("target_language", turn.targetLanguage).put("source_text", turn.sourceText).put("translated_text", turn.translatedText).put("completed_at_millis", turn.completedAtMillis).toString()
            dao.insertSession(HistorySessionEntity(id, turn.userId, turn.mode, turn.completedAtMillis))
            dao.insertTurn(HistoryTurnEntity(turnId, id, turn.userId, turn.sourceLanguage, turn.targetLanguage, cipher.encrypt(turn.sourceText), cipher.encrypt(turn.translatedText), turn.completedAtMillis))
            dao.insertOutbox(HistoryOutboxEntity(ids(), turn.userId, HistoryOperationKind.TURN_UPSERT.name, "turn", turnId, cipher.encrypt(wirePayload), UPSERT_PRIORITY, turn.completedAtMillis, id, turnId))
            id
        }
        runCatching { syncScheduler.schedule(turn.userId) }
        sessionId
    }

    suspend fun renameSession(userId: String, sessionId: String, title: String, updatedAtMillis: Long) {
        val normalized = title.trim(); require(normalized.isNotEmpty())
        database.withTransaction {
            val current = dao.sessions(userId).firstOrNull { it.id == sessionId } ?: return@withTransaction
            if (dao.hasSessionTombstone(userId, sessionId)) return@withTransaction
            dao.upsertSession(current.copy(titleEncrypted = cipher.encrypt(normalized), titleUpdatedAtMillis = updatedAtMillis))
            dao.insertOutbox(HistoryOutboxEntity(ids(), userId, HistoryOperationKind.TITLE_PATCH.name, "session", sessionId, cipher.encrypt(normalized), UPSERT_PRIORITY, updatedAtMillis, sessionId))
        }; runCatching { syncScheduler.schedule(userId) }
    }

    suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long) {
        database.withTransaction {
            // Keep stable turn identities revoked even if an initial null-session request retries.
            dao.turnsForSession(userId, sessionId).forEach {
                dao.insertTombstone(HistoryTombstoneEntity(ids(), userId, "turn", it.id, deletedAtMillis))
            }
            dao.deleteTurnsForSession(userId, sessionId); dao.deleteSession(userId, sessionId)
            dao.insertTombstone(HistoryTombstoneEntity(ids(), userId, "session", sessionId, deletedAtMillis))
            dao.deleteStaleSessionOutbox(userId, sessionId)
            dao.insertOutbox(HistoryOutboxEntity(ids(), userId, "DELETE", "session", sessionId, cipher.encrypt(""), DELETE_PRIORITY, deletedAtMillis, sessionId))
        }; runCatching { syncScheduler.schedule(userId) }
    }

    suspend fun clearAll(userId: String, nowMillis: Long) = dao.sessions(userId).forEach { deleteSession(userId, it.id, nowMillis) }
    suspend fun discardUser(userId: String): Unit = withContext(Dispatchers.IO) {
        accountMutex.withLock {
            val context = checkNotNull(accountContext) { "history_account_context_required" }
            synchronized(epochLock) {
                val epoch = captureAccountEpoch(context, userId)
                check(epoch < Long.MAX_VALUE) { "history_account_epoch_exhausted" }
                // Commit before clearing: a crash here must still revoke old writers after restart.
                check(context.getSharedPreferences(EPOCH_PREFERENCES, Context.MODE_PRIVATE).edit().putLong(epochKey(userId), epoch + 1).putBoolean(revokedKey(userId), true).commit()) { "history_account_epoch_write_failed" }
            }
            database.withTransaction { dao.clearUser(userId) }
            runCatching { syncScheduler.cancel(userId) }
            Unit
        }
    }
    suspend fun quotaExceeded(userId: String): Boolean = dao.quotaExceeded(userId) ?: false

    fun observeHistory(userId: String): Flow<List<HistorySession>> = combine(dao.observeSessions(userId), dao.observeTurns(userId)) { sessions, turns ->
        sessions.map { session -> HistorySession(session.id, session.kind, session.titleEncrypted?.let(cipher::decrypt), session.createdAtMillis, turns.filter { it.sessionId == session.id }.map { turn -> HistoryTurn(turn.id, turn.sourceLanguage, turn.targetLanguage, cipher.decrypt(turn.sourceTextEncrypted), cipher.decrypt(turn.translatedTextEncrypted), turn.completedAtMillis) }) }
    }

    /** Push is always followed by pull; pull also runs with an empty outbox. */
    suspend fun sync(userId: String, transport: LocalHistoryTransport): HistorySyncResult {
        val epoch = accountMutex.withLock { accountContext?.let { currentEpoch(userId) } }
        var activeTransport = transport
        return try {
            suspend fun ensureActive() {
                if ((epoch != null && epoch != currentEpoch(userId)) ||
                    accountContext?.let { !isAccountActive(it, userId) } == true ||
                    !activeTransport.isAccountActive(userId)) {
                    throw CloudApiException("history_account_inactive", 401)
                }
            }
            // Check the local revocation before even verifying credentials over the network.
            if (accountContext?.let { !isAccountActive(it, userId) } == true) {
                throw CloudApiException("history_account_inactive", 401)
            }
            activeTransport = if (transport is CloudHistoryTransport && accountContext != null) {
                withContext(Dispatchers.IO) { CloudHistoryTransport.forAccount(requireNotNull(accountContext), userId) }
                    ?: throw CloudApiException("history_account_inactive", 401)
            } else transport
            ensureActive()
            val pending = dao.pendingOutbox(userId, OUTBOX_BATCH)
            if (pending.isNotEmpty()) {
                val request = pending.map { operation -> operation.toCloudOperation(cipher) }
                ensureActive()
                activeTransport.push(request)
                accountMutex.withLock {
                    ensureActive()
                    dao.deleteOutbox(pending.map { it.id })
                }
            }
            do {
                ensureActive()
                val currentCursor = dao.cursor(userId)?.let { cipher.decrypt(it.cursorEncrypted).toLong() } ?: 0L
                val page = activeTransport.pull(currentCursor)
                accountMutex.withLock {
                    ensureActive()
                    database.withTransaction { applyPage(userId, page) }
                }
            } while (page.hasMore)
            HistorySyncResult(success = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CloudApiException) {
            if (error.statusCode == 409 && error.message?.contains("history_limit_exceeded") == true) {
                accountMutex.withLock {
                    if ((epoch == null || epoch == currentEpoch(userId)) &&
                        accountContext?.let { isAccountActive(it, userId) } != false && activeTransport.isAccountActive(userId)) {
                        dao.upsertSyncState(HistorySyncStateEntity(userId, true))
                    }
                }
            }
            HistorySyncResult(false, when {
                error.statusCode == 401 -> HistorySyncFailure.UNAUTHORIZED
                error.statusCode != null && error.statusCode >= 500 -> HistorySyncFailure.SERVER
                error.statusCode != null -> HistorySyncFailure.INVALID_RESPONSE
                else -> HistorySyncFailure.NETWORK
            })
        } catch (error: org.json.JSONException) {
            HistorySyncResult(false, HistorySyncFailure.REMOTE_HISTORY_DATA, error.javaClass.simpleName)
        } catch (error: IllegalStateException) {
            HistorySyncResult(false, HistorySyncFailure.REMOTE_HISTORY_DATA, error.javaClass.simpleName)
        } catch (error: android.database.sqlite.SQLiteException) {
            HistorySyncResult(false, HistorySyncFailure.LOCAL_DATABASE, error.javaClass.simpleName)
        } catch (error: java.security.GeneralSecurityException) {
            HistorySyncResult(false, HistorySyncFailure.LOCAL_DATA, error.javaClass.simpleName)
        } catch (error: Exception) { HistorySyncResult(false, HistorySyncFailure.UNKNOWN, error.javaClass.simpleName) }
    }

    private suspend fun applyPage(userId: String, page: com.verba.interpretation.cloud.HistoryPullResponse) {
        page.changes.forEach { change ->
            val session = change.session
            if (session.deletedAtMillis != null) {
                dao.turnsForSession(userId, session.id).forEach {
                    dao.insertTombstone(HistoryTombstoneEntity(ids(), userId, "turn", it.id, session.deletedAtMillis))
                }
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
    companion object {
        private const val EPOCH_PREFERENCES = "local_history_account_epochs"
        private val accountMutex = Mutex()
        private val epochLock = Any()
        private fun epochKey(userId: String) = "epoch:$userId"
        private fun revokedKey(userId: String) = "revoked:$userId"

        /** Local revocation guard for workers; credentials must also be pinned and owner-verified. */
        fun isAccountActive(context: Context, userId: String): Boolean = synchronized(epochLock) {
            !context.applicationContext.getSharedPreferences(EPOCH_PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(revokedKey(userId), false)
        }

        /** Capture at account/session binding, never at retry time. First prefs read may block. */
        fun captureAccountEpoch(context: Context, userId: String): Long = synchronized(epochLock) {
            require(userId.isNotBlank())
            context.applicationContext.getSharedPreferences(EPOCH_PREFERENCES, Context.MODE_PRIVATE).getLong(epochKey(userId), 0L)
        }

        fun create(context: Context): LocalHistoryRepository = LocalHistoryRepository(context, HistoryDatabase.create(context), AndroidKeystoreHistoryPayloadCipher(), WorkManagerHistorySyncScheduler(WorkManager.getInstance(context.applicationContext)))
    }
}

private fun JSONObject.required(key: String): String = optString(key).trim().takeIf { it.isNotEmpty() } ?: error("Missing history $key")

class LocalHistorySyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val userId = inputData.getString(USER_ID_KEY) ?: return Result.failure()
        if (!LocalHistoryRepository.isAccountActive(applicationContext, userId)) return Result.success()
        val api = CloudApi(CloudEndpointSettings(applicationContext), KeystoreTokenStore(applicationContext), SharedPreferencesInstallationIdStore(applicationContext))
        if (!api.hasCredentials()) return Result.success()
        // sync() pins credentials and verifies users/me before touching the outbox or cloud.
        val result = LocalHistoryRepository.create(applicationContext).sync(userId, CloudHistoryTransport(api))
        return if (result.success) Result.success() else Result.retry()
    }
}
