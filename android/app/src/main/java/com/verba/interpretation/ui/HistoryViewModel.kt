package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.history.HistorySession
import com.verba.interpretation.history.LocalHistoryRepository
import com.verba.interpretation.history.HistoryTurn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val DELETE_UNDO_WINDOW_MILLIS = 5_000L
private const val MAX_TITLE_LENGTH = 80

interface HistoryRepository {
    fun observeHistory(userId: String): Flow<List<HistorySession>>
    suspend fun renameSession(userId: String, sessionId: String, title: String, updatedAtMillis: Long)
    suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long)
    suspend fun clearAll(userId: String, nowMillis: Long)
}

private class LocalHistoryRepositoryAdapter(private val repository: LocalHistoryRepository) : HistoryRepository {
    override fun observeHistory(userId: String): Flow<List<HistorySession>> = repository.observeHistory(userId)
    override suspend fun renameSession(userId: String, sessionId: String, title: String, updatedAtMillis: Long) =
        repository.renameSession(userId, sessionId, title, updatedAtMillis)
    override suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long) =
        repository.deleteSession(userId, sessionId, deletedAtMillis)
    override suspend fun clearAll(userId: String, nowMillis: Long) = repository.clearAll(userId, nowMillis)
}

data class PendingHistoryDelete(val session: HistorySession, val expiresAtMillis: Long)

data class HistoryUiState(
    val sessions: List<HistorySession> = emptyList(),
    val query: String = "",
    val pendingDeletes: List<PendingHistoryDelete> = emptyList(),
    val clearConfirmationVisible: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleSessions: List<HistorySession>
        get() {
            val pendingIds = pendingDeletes.mapTo(hashSetOf()) { it.session.id }
            val needle = query.trim().lowercase(Locale.ROOT)
            return sessions.asSequence()
                .filterNot { it.id in pendingIds }
                .filter { session ->
                    needle.isEmpty() || session.title?.lowercase(Locale.ROOT)?.contains(needle) == true ||
                        session.turns.any { turn ->
                            turn.sourceText.lowercase(Locale.ROOT).contains(needle) ||
                                turn.translatedText.lowercase(Locale.ROOT).contains(needle) ||
                                turn.sourceLanguage.lowercase(Locale.ROOT).contains(needle) ||
                                turn.targetLanguage.lowercase(Locale.ROOT).contains(needle)
                        }
                }.toList()
        }
}

class HistoryViewModel(
    application: Application,
    private val historyRepository: HistoryRepository = LocalHistoryRepositoryAdapter(LocalHistoryRepository.create(application)),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()
    private var userId: String? = null
    private var accountGeneration = 0L
    private var observation: Job? = null
    private var clearJob: Job? = null
    private val deleteJobs = mutableMapOf<String, Job>()

    fun load(userId: String?) {
        if (userId == this.userId) return
        accountGeneration += 1
        val generation = accountGeneration
        this.userId = userId
        observation?.cancel()
        clearJob?.cancel()
        deleteJobs.values.forEach(Job::cancel)
        deleteJobs.clear()
        mutableState.value = HistoryUiState()
        if (userId == null) return
        observation = viewModelScope.launch(dispatcher) {
            historyRepository.observeHistory(userId).collect { sessions ->
                if (isCurrentAccount(userId, generation)) {
                    mutableState.value = mutableState.value.copy(sessions = sessions)
                }
            }
        }
    }

    fun setQuery(query: String) {
        mutableState.value = mutableState.value.copy(query = query)
    }

    /** Returns false without calling the repository when the title is unsafe or empty. */
    fun rename(sessionId: String, title: String): Boolean {
        val normalized = title.trim()
        val validationError = when {
            normalized.isEmpty() -> "标题不能为空"
            normalized.length > MAX_TITLE_LENGTH -> "标题最多 80 个字符"
            normalized.any { it == '\n' || it == '\r' } -> "标题不能包含换行"
            else -> null
        }
        if (validationError != null) {
            reportError(validationError)
            return false
        }
        val id = userId ?: run {
            reportError("无法保存标题，请先登录")
            return false
        }
        val generation = accountGeneration
        viewModelScope.launch(dispatcher) {
            try {
                historyRepository.renameSession(id, sessionId, normalized, nowMillis())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentAccount(id, generation)) reportError("保存标题失败，请稍后重试")
            }
        }
        return true
    }

    fun requestDelete(sessionId: String) {
        val id = userId ?: return
        val session = mutableState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        if (mutableState.value.pendingDeletes.any { it.session.id == sessionId }) return
        val pending = PendingHistoryDelete(session, nowMillis() + DELETE_UNDO_WINDOW_MILLIS)
        mutableState.value = mutableState.value.copy(pendingDeletes = mutableState.value.pendingDeletes + pending)
        deleteJobs[sessionId]?.cancel()
        val generation = accountGeneration
        deleteJobs[sessionId] = viewModelScope.launch(dispatcher) {
            delay(DELETE_UNDO_WINDOW_MILLIS)
            commitDelete(id, generation, sessionId, pending.expiresAtMillis)
        }
    }

    fun undoDelete(sessionId: String) {
        deleteJobs.remove(sessionId)?.cancel()
        mutableState.value = mutableState.value.copy(
            pendingDeletes = mutableState.value.pendingDeletes.filterNot { it.session.id == sessionId },
        )
    }

    /** Commits one expired item; each delete owns an independent five-second window. */
    private suspend fun commitDelete(id: String, generation: Long, sessionId: String, expiresAtMillis: Long) {
        val pending = mutableState.value.pendingDeletes.firstOrNull { it.session.id == sessionId }
        if (!isCurrentAccount(id, generation) || pending == null || pending.expiresAtMillis != expiresAtMillis) return
        try {
            historyRepository.deleteSession(id, sessionId, nowMillis())
            if (isCurrentAccount(id, generation)) {
                mutableState.value = mutableState.value.copy(
                    pendingDeletes = mutableState.value.pendingDeletes.filterNot { it.session.id == sessionId },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (isCurrentAccount(id, generation)) {
                mutableState.value = mutableState.value.copy(
                    pendingDeletes = mutableState.value.pendingDeletes.filterNot { it.session.id == sessionId },
                )
                reportError("删除失败，记录仍保留在本机")
            }
        } finally {
            if (deleteJobs[sessionId] === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                deleteJobs.remove(sessionId)
            }
        }
    }

    fun showClearConfirmation() {
        mutableState.value = mutableState.value.copy(clearConfirmationVisible = true)
    }

    fun dismissClearConfirmation() {
        mutableState.value = mutableState.value.copy(clearConfirmationVisible = false)
    }

    fun clearAll() {
        val id = userId ?: run {
            reportError("无法清空历史，请先登录")
            return
        }
        val generation = accountGeneration
        deleteJobs.values.forEach(Job::cancel)
        deleteJobs.clear()
        mutableState.value = mutableState.value.copy(pendingDeletes = emptyList())
        clearJob?.cancel()
        clearJob = viewModelScope.launch(dispatcher) {
            try {
                historyRepository.clearAll(id, nowMillis())
                if (isCurrentAccount(id, generation)) {
                    mutableState.value = mutableState.value.copy(clearConfirmationVisible = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentAccount(id, generation)) reportError("清空失败，记录仍保留在本机")
            }
        }
    }

    fun exportSession(sessionId: String): String =
        mutableState.value.sessions.firstOrNull { it.id == sessionId }?.let { formatSessions(listOf(it)) }.orEmpty()

    fun exportVisibleResults(): String = formatSessions(mutableState.value.visibleSessions)

    fun exportAll(): String = formatSessions(mutableState.value.sessions)

    /** Compatibility helper: a supplied session means one session; otherwise current search results. */
    fun export(session: HistorySession? = null): String = session?.let { exportSession(it.id) } ?: exportVisibleResults()

    fun clearError() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    private fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(errorMessage = message)
    }

    private fun isCurrentAccount(id: String, generation: Long): Boolean =
        userId == id && accountGeneration == generation

    private fun formatSessions(sessions: List<HistorySession>): String = sessions.joinToString("\n\n") { session ->
        buildString {
            append(session.title ?: defaultTitle(session.kind))
            append("\n时间：").append(formatTime(session.createdAtMillis))
            append("\n记录数：").append(session.turns.size)
            session.turns.forEachIndexed { index, turn ->
                append("\n\n第").append(index + 1).append("条 · ")
                append(TranslationLanguage.displayName(turn.sourceLanguage)).append(" → ")
                    .append(TranslationLanguage.displayName(turn.targetLanguage))
                append("\n原文：").append(turn.sourceText)
                append("\n译文：").append(turn.translatedText)
            }
        }
    }

    private fun formatTime(millis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))

    private fun defaultTitle(kind: String): String = if (kind == "face_to_face") "面对面翻译" else "同传翻译"
}
