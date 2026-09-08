package com.verba.interpretation.ui.history

import com.verba.interpretation.history.CompletedTurn
import com.verba.interpretation.history.LocalHistorySaveController
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus
import com.verba.interpretation.history.LocalHistoryTurnSaver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the save-feedback policy: local history saves silently on success (no per-turn
 * "加入历史记录" notice), and only failures become visible, with a fixed safe copy that can
 * never leak error detail. The save state machine itself is unchanged — SAVING/SAVED are still
 * published, they simply have no renderable feedback.
 */
class LocalHistorySaveFeedbackPolicyTest {
    @Test
    fun successfulSaveStaysSilentThroughTheRealStateMachine() = runBlocking {
        val saves = GateSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "solo")
        val turn = requireNotNull(controller.bindTurn("turn-1"))
        val job = requireNotNull(controller.saveTurn(turn, "zh", "en", "你好", "hello", 1))

        waitFor { controller.state.value.status == LocalHistorySaveStatus.SAVING }
        assertNull("in-flight saves must not render feedback", controller.state.value.visibleFeedback())

        saves.gate("你好").complete(conversation.sessionId)
        job.join()
        assertEquals(LocalHistorySaveStatus.SAVED, controller.state.value.status)
        assertNull("a completed save must not render feedback", controller.state.value.visibleFeedback())
    }

    @Test
    fun repeatedSuccessfulSavesStaySilent() = runBlocking {
        val saves = GateSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "face")
        val first = requireNotNull(controller.bindTurn("turn-1"))
        val second = requireNotNull(controller.bindTurn("turn-2"))
        val firstJob = requireNotNull(controller.saveTurn(first, "zh", "en", "一", "one", 1))
        val secondJob = requireNotNull(controller.saveTurn(second, "zh", "en", "二", "two", 2))
        saves.gate("一").complete(conversation.sessionId)
        saves.gate("二").complete(conversation.sessionId)
        firstJob.join()
        secondJob.join()

        assertEquals(LocalHistorySaveStatus.SAVED, controller.state.value.status)
        assertEquals(0, controller.state.value.pendingCount)
        assertNull(controller.state.value.visibleFeedback())
    }

    @Test
    fun failedSaveShowsFixedSafeCopyWithoutLeakingErrorDetail() = runBlocking {
        val secret = "token=SECRET-9f /data/user/0/databases/history.sqlite"
        val saves = GateSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "solo")
        val turn = requireNotNull(controller.bindTurn("turn-1"))
        val job = requireNotNull(controller.saveTurn(turn, "zh", "en", "一", "one", 1))
        saves.gate("一").completeExceptionally(IllegalStateException("provider leaked $secret"))

        job.join()
        assertEquals(LocalHistorySaveStatus.FAILED, controller.state.value.status)
        val feedback = requireNotNull(controller.state.value.visibleFeedback()) {
            "failure visibility must not be removed: " + controller.state.value
        }
        assertEquals(LOCAL_HISTORY_SAVE_FAILED_TEXT, feedback.text)
        assertFalse("feedback leaked the raw exception", feedback.text.contains(secret))
        assertFalse("feedback leaked a path", feedback.text.contains("/data/user"))
        assertFalse("feedback leaked an exception class", feedback.text.contains("IllegalStateException"))
        assertEquals("local_save_failed", controller.state.value.errorCode)
    }

    @Test
    fun everyStatusMapsToSilenceOrTheFixedSafeCopyRegardlessOfErrorCode() {
        val hostileCodes = listOf(
            "local_save_failed",
            "history_limit_exceeded",
            "invalid_history_payload",
            "invalid_local_save_result",
            "local_save_cancelled",
            "IllegalStateException: /data/user/0/history.sqlite token=SECRET",
            "",
        )
        for (status in LocalHistorySaveStatus.values()) {
            for (errorCode in hostileCodes + null) {
                val feedback = LocalHistorySaveState(status, "session-1", errorCode, 0).visibleFeedback()
                if (status == LocalHistorySaveStatus.FAILED) {
                    assertEquals("failure copy must stay fixed", LOCAL_HISTORY_SAVE_FAILED_TEXT, requireNotNull(feedback).text)
                } else {
                    assertNull("$status must stay silent regardless of errorCode", feedback)
                }
            }
        }
        for (code in hostileCodes.filter { it.isNotBlank() }) {
            assertFalse("safe copy must not embed error codes", LOCAL_HISTORY_SAVE_FAILED_TEXT.contains(code))
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            delay(10)
        }
        error("condition did not become true")
    }

    /** Saver whose per-source-text results are released manually by the test. */
    private class GateSaver : LocalHistoryTurnSaver {
        private val gates = mutableMapOf<String, CompletableDeferred<String>>()
        fun gate(sourceText: String): CompletableDeferred<String> = synchronized(this) {
            gates.getOrPut(sourceText) { CompletableDeferred() }
        }

        override suspend fun save(turn: CompletedTurn): String = gate(turn.sourceText).await()
    }
}
