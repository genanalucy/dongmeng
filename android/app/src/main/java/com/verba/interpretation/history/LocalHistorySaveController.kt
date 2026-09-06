package com.verba.interpretation.history

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The status of the device-local write, not the cloud synchronization status. */
enum class LocalHistorySaveStatus { IDLE, SAVING, SAVED, FAILED }

data class LocalHistorySaveState(
    val status: LocalHistorySaveStatus = LocalHistorySaveStatus.IDLE,
    val sessionId: String? = null,
    val errorCode: String? = null,
    val pendingCount: Int = 0,
)

data class LocalHistoryConversation(
    val conversationId: String,
    val userId: String,
    val mode: String,
    val sessionId: String,
)

data class LocalHistoryTurnOwnership(
    val turnId: String,
    val conversationId: String,
    val userId: String,
    val mode: String,
    val sessionId: String,
)

fun interface LocalHistoryTurnSaver {
    suspend fun save(turn: CompletedTurn): String
}

/**
 * Binds each turn to the local conversation and keeps writes alive across pause/resume and
 * communication replacement. Old writes are deliberately not cancelled: their result is only
 * published while their conversation is current.
 */
class LocalHistorySaveController(
    private val saver: LocalHistoryTurnSaver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private data class Counters(
        val conversation: LocalHistoryConversation,
        var pending: Int = 0,
        var succeeded: Int = 0,
        var failed: Int = 0,
        var errorCode: String? = null,
        var paused: Boolean = false,
    )

    private val lock = Any()
    private val mutableState = MutableStateFlow(LocalHistorySaveState())
    val state: StateFlow<LocalHistorySaveState> = mutableState.asStateFlow()
    private val conversations = mutableMapOf<String, Counters>()
    private val submitted = mutableSetOf<String>()
    private var current: Counters? = null

    fun startConversation(userId: String, mode: String): LocalHistoryConversation = synchronized(lock) {
        require(userId.isNotBlank() && mode.isNotBlank())
        val conversation = LocalHistoryConversation(ids(), userId, mode, ids())
        val counters = Counters(conversation)
        conversations[conversation.conversationId] = counters
        current = counters
        publishLocked(counters)
        conversation
    }

    fun currentConversation(): LocalHistoryConversation? = synchronized(lock) { current?.conversation }

    /** Pausing does not create a new local history session. */
    fun pauseConversation() = synchronized(lock) {
        current?.paused = true
    }

    /** Resuming returns the same ownership boundary created by startConversation. */
    fun resumeConversation(): LocalHistoryConversation? = synchronized(lock) {
        val counters = current ?: return@synchronized null
        counters.paused = false
        counters.conversation
    }

    /** Ends only the UI's current view; already queued writes remain durable work. */
    fun endConversation() = synchronized(lock) {
        current = null
        mutableState.value = LocalHistorySaveState()
    }

    fun bindTurn(turnId: String): LocalHistoryTurnOwnership? = synchronized(lock) {
        val counters = current ?: return@synchronized null
        if (counters.paused) return@synchronized null
        counters.conversation.let { conversation ->
            LocalHistoryTurnOwnership(turnId, conversation.conversationId, conversation.userId, conversation.mode, conversation.sessionId)
        }
    }

    fun saveTurn(
        ownership: LocalHistoryTurnOwnership,
        sourceLanguage: String,
        targetLanguage: String,
        sourceText: String,
        translatedText: String,
        completedAtMillis: Long,
    ): Job? {
        val normalizedSource = sourceText.trim()
        val normalizedTranslation = translatedText.trim()
        if (normalizedSource.isEmpty() || normalizedTranslation.isEmpty()) return null

        val requestKey = "${ownership.conversationId}:${ownership.turnId}"
        synchronized(lock) {
            val counters = conversations[ownership.conversationId] ?: return null
            if (counters.conversation.userId != ownership.userId || counters.conversation.sessionId != ownership.sessionId || counters.conversation.mode != ownership.mode) return null
            if (!submitted.add(requestKey)) return null
            counters.pending += 1
            if (current === counters) publishLocked(counters)
        }

        return scope.launch {
            try {
                val savedSessionId = saver.save(
                    CompletedTurn(
                        userId = ownership.userId,
                        localSessionId = ownership.sessionId,
                        mode = ownership.mode,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        sourceText = normalizedSource,
                        translatedText = normalizedTranslation,
                        completedAtMillis = completedAtMillis,
                    ),
                )
                check(savedSessionId == ownership.sessionId) { "local_save_session_mismatch" }
                complete(ownership.conversationId, succeeded = true, errorCode = null)
            } catch (error: CancellationException) {
                complete(ownership.conversationId, succeeded = false, errorCode = "local_save_cancelled")
                throw error
            } catch (error: Throwable) {
                complete(ownership.conversationId, succeeded = false, errorCode = safeErrorCode(error))
            }
        }
    }

    private fun complete(conversationId: String, succeeded: Boolean, errorCode: String?) = synchronized(lock) {
        val counters = conversations[conversationId] ?: return
        counters.pending -= 1
        if (succeeded) counters.succeeded += 1 else {
            counters.failed += 1
            counters.errorCode = errorCode
        }
        if (current === counters) publishLocked(counters)
    }

    private fun publishLocked(counters: Counters) {
        val status = when {
            counters.pending > 0 -> LocalHistorySaveStatus.SAVING
            counters.failed > 0 -> LocalHistorySaveStatus.FAILED
            counters.succeeded > 0 -> LocalHistorySaveStatus.SAVED
            else -> LocalHistorySaveStatus.IDLE
        }
        mutableState.value = LocalHistorySaveState(status, counters.conversation.sessionId, counters.errorCode, counters.pending)
    }

    private fun safeErrorCode(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("history_limit_exceeded") -> "history_limit_exceeded"
            message.contains("Missing history") -> "invalid_history_payload"
            message.contains("local_save_session_mismatch") -> "invalid_local_save_result"
            else -> "local_save_failed"
        }
    }
}
