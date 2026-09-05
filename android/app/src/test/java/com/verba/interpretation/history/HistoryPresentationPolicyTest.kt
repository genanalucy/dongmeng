package com.verba.interpretation.history

import com.verba.interpretation.ui.HistoryUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPresentationPolicyTest {
    private val session = HistorySession("s", "solo", "旅行", 1, listOf(HistoryTurn("t", "zh", "en", "北京", "Beijing", 2)))

    @Test fun searchMatchesEncryptedContentAfterRepositoryMapping() {
        assertEquals(listOf(session), HistoryUiState(sessions = listOf(session), query = "beijing").visibleSessions)
        assertTrue(HistoryUiState(sessions = listOf(session), query = "不存在").visibleSessions.isEmpty())
    }
}
