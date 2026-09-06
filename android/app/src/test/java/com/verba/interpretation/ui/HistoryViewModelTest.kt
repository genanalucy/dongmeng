package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.history.HistorySession
import com.verba.interpretation.history.HistoryTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun eachDeleteHasAnIndependentFiveSecondUndoWindow() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        repository.emit("user-1", listOf(session("first"), session("second")))
        advanceUntilIdle()

        viewModel.requestDelete("first")
        advanceTimeBy(2_500)
        viewModel.requestDelete("second")
        advanceTimeBy(2_499)
        assertEquals(emptyList<String>(), repository.deletedIds)
        assertEquals(listOf("first", "second"), viewModel.state.value.pendingDeletes.map { it.session.id })

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("first"), repository.deletedIds)
        assertEquals(listOf("second"), viewModel.state.value.pendingDeletes.map { it.session.id })
        advanceTimeBy(2_500)
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), repository.deletedIds)
    }

    @Test
    fun failedDeleteRemovesPendingAndMakesSessionVisibleAgain() = runTest(dispatcher) {
        val repository = FakeHistoryRepository(deleteFailure = true)
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        repository.emit("user-1", listOf(session("first")))
        advanceUntilIdle()

        viewModel.requestDelete("first")
        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.visibleSessions.any { it.id == "first" })
        assertTrue(viewModel.state.value.pendingDeletes.isEmpty())
        assertEquals("删除失败，记录仍保留在本机", viewModel.state.value.errorMessage)
    }

    @Test
    fun switchingAccountsCancelsOldDeleteAndResetsState() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        repository.emit("user-1", listOf(session("old")))
        advanceUntilIdle()
        viewModel.requestDelete("old")

        viewModel.load("user-2")
        assertTrue(viewModel.state.value.sessions.isEmpty())
        assertTrue(viewModel.state.value.pendingDeletes.isEmpty())
        repository.emit("user-2", listOf(session("new")))
        advanceTimeBy(5_001)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), repository.deletedIds)
        assertEquals(listOf("new"), viewModel.state.value.sessions.map { it.id })
        assertFalse(viewModel.state.value.errorMessage == "删除失败，记录仍保留在本机")
    }

    @Test
    fun exportAllIsNotRestrictedBySearchWhileVisibleExportIs() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        repository.emit("user-1", listOf(session("first", "alpha"), session("second", "beta")))
        advanceUntilIdle()

        viewModel.setQuery("alpha")
        val visible = viewModel.exportVisibleResults()
        val all = viewModel.exportAll()

        assertTrue(visible.contains("first"))
        assertFalse(visible.contains("second"))
        assertTrue(all.contains("first"))
        assertTrue(all.contains("second"))
    }

    @Test
    fun compatibilityExportResolvesIdFromCurrentAccountInsteadOfUsingStaleObject() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        val oldAccountSession = session("shared-id", "old-account-secret")
        repository.emit("user-1", listOf(oldAccountSession))
        advanceUntilIdle()

        viewModel.load("user-2")
        repository.emit("user-2", listOf(session("shared-id", "current-account-text")))
        advanceUntilIdle()

        val exported = viewModel.export(oldAccountSession)
        assertTrue(exported.contains("current-account-text"))
        assertFalse(exported.contains("old-account-secret"))
    }

    @Test
    fun compatibilityExportReturnsEmptyWhenStaleObjectIdIsAbsentFromCurrentAccount() = runTest(dispatcher) {
        val repository = FakeHistoryRepository()
        val viewModel = viewModel(repository)
        viewModel.load("user-1")
        val oldAccountSession = session("old-only", "old-account-secret")
        repository.emit("user-1", listOf(oldAccountSession))
        advanceUntilIdle()

        viewModel.load("user-2")
        repository.emit("user-2", listOf(session("new-only", "current-account-text")))
        advanceUntilIdle()

        assertEquals("", viewModel.export(oldAccountSession))
    }

    private fun viewModel(repository: FakeHistoryRepository): HistoryViewModel =
        HistoryViewModel(Application(), repository, dispatcher, nowMillis = { 0L })

    private fun session(id: String, text: String = id): HistorySession = HistorySession(
        id = id,
        kind = "interpretation",
        title = id,
        createdAtMillis = 0L,
        turns = listOf(HistoryTurn("turn-$id", "en", "zh", text, "译文-$text", 0L)),
    )
}

private class FakeHistoryRepository(
    private val deleteFailure: Boolean = false,
) : HistoryRepository {
    private val histories = mutableMapOf<String, MutableStateFlow<List<HistorySession>>>()
    val deletedIds = mutableListOf<String>()

    override fun observeHistory(userId: String): Flow<List<HistorySession>> =
        histories.getOrPut(userId) { MutableStateFlow(emptyList()) }

    fun emit(userId: String, sessions: List<HistorySession>) {
        histories.getOrPut(userId) { MutableStateFlow(emptyList()) }.value = sessions
    }

    override suspend fun renameSession(userId: String, sessionId: String, title: String, updatedAtMillis: Long) = Unit

    override suspend fun deleteSession(userId: String, sessionId: String, deletedAtMillis: Long) {
        if (deleteFailure) error("delete failed")
        deletedIds += sessionId
    }

    override suspend fun clearAll(userId: String, nowMillis: Long) = Unit
}
