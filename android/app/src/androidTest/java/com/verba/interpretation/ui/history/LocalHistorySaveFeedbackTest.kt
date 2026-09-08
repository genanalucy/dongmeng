package com.verba.interpretation.ui.history

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Successful and in-flight local saves render nothing; only the failure notice renders, and it
 * carries a polite live region so screen readers announce it without stealing focus.
 */
class LocalHistorySaveFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun idleRendersNothing() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.IDLE))
        assertSilent()
    }

    @Test
    fun savingRendersNothing() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.SAVING, sessionId = "session-saving", pendingCount = 1))
        assertSilent()
    }

    @Test
    fun savedRendersNothing() {
        val opened = mutableListOf<String>()
        setFeedback(
            LocalHistorySaveState(status = LocalHistorySaveStatus.SAVED, sessionId = "session-saved"),
            canOpenHistory = true,
            onViewHistory = opened::add,
        )
        assertSilent()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun failedShowsSafeCopyAsPoliteLiveRegion() {
        setFeedback(LocalHistorySaveState(status = LocalHistorySaveStatus.FAILED, sessionId = "session-failed"))
        compose.onNodeWithText(LOCAL_HISTORY_SAVE_FAILED_TEXT).assert(hasPoliteLiveRegion())
    }

    @Test
    fun failedNeverRendersErrorCodeOrExceptionDetail() {
        setFeedback(
            LocalHistorySaveState(
                status = LocalHistorySaveStatus.FAILED,
                sessionId = "session-failed",
                errorCode = "IllegalStateException: /data/user/0/history.sqlite token=SECRET",
            )
        )
        compose.onNodeWithText(LOCAL_HISTORY_SAVE_FAILED_TEXT).assertExists()
        compose.onNodeWithText("IllegalStateException", substring = true).assertDoesNotExist()
        compose.onNodeWithText("/data/user/0", substring = true).assertDoesNotExist()
        compose.onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        compose.onNodeWithText("errorCode", substring = true).assertDoesNotExist()
    }

    private fun assertSilent() {
        compose.onNodeWithText("已保存到本机").assertDoesNotExist()
        compose.onNodeWithText("正在保存到本机…").assertDoesNotExist()
        compose.onNodeWithText("查看本次记录").assertDoesNotExist()
        compose.onNodeWithText("结束翻译后可查看记录").assertDoesNotExist()
        compose.onNodeWithText(LOCAL_HISTORY_SAVE_FAILED_TEXT).assertDoesNotExist()
    }

    private fun hasPoliteLiveRegion() = SemanticsMatcher("has polite live region") { node ->
        node.config.any { it.key == SemanticsProperties.LiveRegion && it.value == LiveRegionMode.Polite }
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
