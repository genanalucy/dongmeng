package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.history.HistorySession
import com.verba.interpretation.history.LocalHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
    val sessions: List<HistorySession> = emptyList(),
    val query: String = "",
    val pendingDelete: HistorySession? = null,
    val clearConfirmationVisible: Boolean = false,
) {
    val visibleSessions: List<HistorySession> get() {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return sessions
        return sessions.filter { session ->
            session.title?.lowercase()?.contains(needle) == true || session.turns.any { turn ->
                turn.sourceText.lowercase().contains(needle) || turn.translatedText.lowercase().contains(needle) ||
                    turn.sourceLanguage.lowercase().contains(needle) || turn.targetLanguage.lowercase().contains(needle)
            }
        }
    }
}

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalHistoryRepository.create(application)
    private val mutableState = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()
    private var userId: String? = null
    private var observation: Job? = null

    fun load(userId: String?) {
        if (userId == this.userId) return
        this.userId = userId; observation?.cancel()
        if (userId == null) { mutableState.value = HistoryUiState(); return }
        observation = viewModelScope.launch { repository.observeHistory(userId).collect { sessions -> mutableState.value = mutableState.value.copy(sessions = sessions) } }
    }
    fun setQuery(query: String) { mutableState.value = mutableState.value.copy(query = query) }
    fun rename(sessionId: String, title: String) = userId?.let { id -> viewModelScope.launch { repository.renameSession(id, sessionId, title, System.currentTimeMillis()) } }
    fun requestDelete(sessionId: String) { mutableState.value = mutableState.value.copy(pendingDelete = mutableState.value.sessions.firstOrNull { it.id == sessionId }) }
    fun undoDelete() { mutableState.value = mutableState.value.copy(pendingDelete = null) }
    fun confirmDelete() {
        val pending = mutableState.value.pendingDelete ?: return; val id = userId ?: return
        mutableState.value = mutableState.value.copy(pendingDelete = null)
        viewModelScope.launch { repository.deleteSession(id, pending.id, System.currentTimeMillis()) }
    }
    fun showClearConfirmation() { mutableState.value = mutableState.value.copy(clearConfirmationVisible = true) }
    fun dismissClearConfirmation() { mutableState.value = mutableState.value.copy(clearConfirmationVisible = false) }
    fun clearAll() = userId?.let { id -> viewModelScope.launch { repository.clearAll(id, System.currentTimeMillis()); dismissClearConfirmation() } }
    fun export(session: HistorySession? = null): String = (session?.let(::listOf) ?: mutableState.value.visibleSessions).flatMap { value -> value.turns.map { "${value.title ?: value.kind}\n${it.sourceText}\n${it.translatedText}" } }.joinToString("\n\n")
}
