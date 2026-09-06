package com.verba.interpretation.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LocalHistorySaveFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun idleDoesNotShowViewAction() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.IDLE))

        assertNoViewAction()
    }

    @Test
    fun savingDoesNotShowViewAction() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.SAVING, sessionId = "session-saving"))

        assertNoViewAction()
    }

    @Test
    fun failedDoesNotShowViewAction() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.FAILED, sessionId = "session-failed"))

        assertNoViewAction()
    }

    @Test
    fun savedWithoutSessionIdDoesNotShowViewAction() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.SAVED, sessionId = null))

        assertNoViewAction()
    }

    @Test
    fun savedActionIsDisabledUntilHistoryCanBeOpened() {
        val opened = mutableListOf<String>()
        setFeedback(
            LocalHistorySaveState(status = LocalHistorySaveStatus.SAVED, sessionId = "session-disabled"),
            canOpenHistory = false,
            onViewHistory = opened::add,
        )

        compose.onNodeWithText("结束翻译后可查看记录").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun savedActionPassesExactSessionIdAndMakesNoSynchronizationClaim() {
        val opened = mutableListOf<String>()
        setFeedback(
            LocalHistorySaveState(status = LocalHistorySaveStatus.SAVED, sessionId = "session/exact-42"),
            canOpenHistory = true,
            onViewHistory = opened::add,
        )

        compose.onNodeWithText("查看本次记录").performClick()

        assertEquals(listOf("session/exact-42"), opened)
        compose.onNodeWithText("同步", substring = true).assertDoesNotExist()
    }

    private fun assertNoViewAction() {
        compose.onNodeWithText("查看本次记录").assertDoesNotExist()
        compose.onNodeWithText("结束翻译后可查看记录").assertDoesNotExist()
    }

    private fun setFeedback(
        state: LocalHistorySaveState,
        canOpenHistory: Boolean = true,
        onViewHistory: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                LocalHistorySaveFeedback(
                    state = state,
                    canOpenHistory = canOpenHistory,
                    onViewHistory = onViewHistory,
                )
            }
        }
    }
}
