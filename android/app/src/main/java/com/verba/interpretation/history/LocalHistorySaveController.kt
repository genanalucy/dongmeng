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

enum class LocalHistorySaveStatus { IDLE, SAVING, SAVED, FAILED }
data class LocalHistorySaveState(
    val status: LocalHistorySaveStatus = LocalHistorySaveStatus.IDLE,
    val sessionId: String? = null,
    val errorCode: String? = null,
    val pendingCount: Int = 0,
    val message: String? = null,
)
data class LocalHistoryConversation(val conversationId: String, val userId: String, val mode: String, val sessionId: String, val accountEpoch: Long = 0)
data class LocalHistoryTurnOwnership(val turnId: String, val conversationId: String, val userId: String, val mode: String, val sessionId: String, val accountEpoch: Long = 0)
fun interface LocalHistoryTurnSaver { suspend fun save(turn: CompletedTurn): String }

/** Application-owned IO, not a view-model job. A save is durable only after its DB
 * transaction commits; committed rows and sync outbox survive process recreation. */
object LocalHistoryWriteScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

class LocalHistorySaveController(
    private val saver: LocalHistoryTurnSaver,
    private val scope: CoroutineScope = LocalHistoryWriteScope.scope,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val accountEpoch: (String) -> Long = { 0L },
) {
    private data class ConversationState(val value: LocalHistoryConversation, var paused: Boolean = false, var finished: Boolean = false)
    private enum class WriteStatus { PENDING, SAVED, FAILED }
    private data class Write(val conversationId: String, val request: CompletedTurn, var status: WriteStatus)
    private val lock = Any()
    private val mutableState = MutableStateFlow(LocalHistorySaveState())
    val state: StateFlow<LocalHistorySaveState> = mutableState.asStateFlow()
    private val conversations = mutableMapOf<String, ConversationState>()
    private val writes = linkedMapOf<String, Write>()
    private var current: ConversationState? = null

    fun startConversation(userId: String, mode: String): LocalHistoryConversation = synchronized(lock) {
        require(userId.isNotBlank() && mode.isNotBlank())
        val value = LocalHistoryConversation(ids(), userId, mode, ids(), accountEpoch(userId))
        current = ConversationState(value).also { conversations[value.conversationId] = it }
        publishLocked()
        value
    }
    fun currentConversation(): LocalHistoryConversation? = synchronized(lock) { current?.takeUnless { it.finished }?.value }
    fun finishConversation() = synchronized(lock) { current?.finished = true }
    fun pauseConversation() = synchronized(lock) { current?.paused = true }
    fun resumeConversation(): LocalHistoryConversation? = synchronized(lock) { current?.also { it.paused = false }?.value }
    fun endConversation() = synchronized(lock) { current = null; publishLocked() }
    fun bindTurn(turnId: String): LocalHistoryTurnOwnership? = synchronized(lock) {
        current?.takeUnless { it.paused || it.finished }?.value?.let { LocalHistoryTurnOwnership(turnId, it.conversationId, it.userId, it.mode, it.sessionId, it.accountEpoch) }
    }
    fun saveTurn(ownership: LocalHistoryTurnOwnership, sourceLanguage: String, targetLanguage: String, sourceText: String, translatedText: String, completedAtMillis: Long): Job? {
        if (sourceText.isBlank() || translatedText.isBlank()) return null
        val key = "${ownership.conversationId}:${ownership.turnId}"
        val write = synchronized(lock) {
            val conversation = conversations[ownership.conversationId]?.value ?: return null
            if (conversation.userId != ownership.userId || conversation.sessionId != ownership.sessionId || conversation.mode != ownership.mode || conversation.accountEpoch != ownership.accountEpoch) return null
            if (writes.containsKey(key)) return null
            Write(ownership.conversationId, CompletedTurn(ownership.userId, ownership.sessionId, ownership.mode, sourceLanguage, targetLanguage, sourceText.trim(), translatedText.trim(), completedAtMillis,
                UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString(), ownership.accountEpoch), WriteStatus.PENDING).also {
                writes[key] = it; publishLocked()
            }
        }
        return launchWrite(write)
    }
    fun explainIfNoCompletedContent() = synchronized(lock) {
        if (mutableState.value.status == LocalHistorySaveStatus.IDLE) {
            mutableState.value = mutableState.value.copy(message = "暂无已完成的双语内容；完整结果会自动保存。")
        }
    }

    fun retryFailed(): List<Job> {
        val retry = synchronized(lock) {
            val id = current?.value?.conversationId ?: return emptyList()
            writes.values.filter { it.conversationId == id && it.status == WriteStatus.FAILED }.onEach { it.status = WriteStatus.PENDING }.also { publishLocked() }
        }
        return retry.map(::launchWrite)
    }
    private fun launchWrite(write: Write): Job = scope.launch {
        var succeeded = false
        var errorCode: String? = null
        try {
            check(saver.save(write.request) == write.request.localSessionId) { "local_save_session_mismatch" }
            succeeded = true
        } catch (error: CancellationException) {
            errorCode = "local_save_cancelled"
            throw error
        } catch (error: Throwable) {
            errorCode = when {
                error.message.orEmpty().contains("history_limit_exceeded") -> "history_limit_exceeded"
                else -> "local_save_failed"
            }
        } finally {
            synchronized(lock) {
                write.status = if (succeeded) WriteStatus.SAVED else WriteStatus.FAILED
                if (!succeeded) failures[write.conversationId] = errorCode ?: "local_save_failed"
                publishLocked()
            }
        }
    }
    private val failures = mutableMapOf<String, String>()
    private fun publishLocked() {
        val active = current ?: run { mutableState.value = LocalHistorySaveState(); return }
        val items = writes.values.filter { it.conversationId == active.value.conversationId }
        val pending = items.count { it.status == WriteStatus.PENDING }
        val failed = items.any { it.status == WriteStatus.FAILED }
        val status = when { pending > 0 -> LocalHistorySaveStatus.SAVING; failed -> LocalHistorySaveStatus.FAILED; items.any { it.status == WriteStatus.SAVED } -> LocalHistorySaveStatus.SAVED; else -> LocalHistorySaveStatus.IDLE }
        mutableState.value = LocalHistorySaveState(status, active.value.sessionId, if (failed) failures[active.value.conversationId] else null, pending)
    }
}
