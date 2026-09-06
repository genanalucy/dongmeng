package com.verba.interpretation.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalHistorySaveControllerTest {
    @Test
    fun pendingSuccessAndFailureDoNotLetOneCompletionHideTheOther() = runBlocking {
        val saves = BlockingSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "solo")
        val first = requireNotNull(controller.bindTurn("first"))
        val second = requireNotNull(controller.bindTurn("second"))

        controller.saveTurn(first, "zh", "en", "一", "one", 1)
        controller.saveTurn(second, "zh", "en", "二", "two", 2)
        saves.awaitStarted("一")
        saves.awaitStarted("二")
        saves.fail("一", IllegalStateException("provider leaked secret"))
        saves.succeed("二")
        waitFor { controller.state.value.status == LocalHistorySaveStatus.FAILED }

        assertEquals(LocalHistorySaveStatus.FAILED, controller.state.value.status)
        assertEquals(0, controller.state.value.pendingCount)
        assertEquals("local_save_failed", controller.state.value.errorCode)
        assertEquals(conversation.sessionId, controller.state.value.sessionId)
    }

    @Test
    fun oldConversationCompletionDoesNotPolluteNewConversation() = runBlocking {
        val saves = BlockingSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val old = controller.startConversation("old-user", "solo")
        val oldTurn = requireNotNull(controller.bindTurn("old-turn"))
        controller.saveTurn(oldTurn, "zh", "en", "旧", "old", 1)
        saves.awaitStarted("旧")
        controller.endConversation()
        val current = controller.startConversation("new-user", "solo")
        saves.fail("旧", RuntimeException("late old failure"))
        waitFor { controller.state.value.sessionId == current.sessionId }

        assertEquals(LocalHistorySaveStatus.IDLE, controller.state.value.status)
        assertEquals(current.sessionId, controller.state.value.sessionId)
        assertNull(controller.state.value.errorCode)
        assertEquals(old.userId, oldTurn.userId)
    }

    @Test
    fun emptyContentAndWrongOwnershipAreNotSubmitted() = runBlocking {
        val saves = BlockingSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "solo")
        val turn = requireNotNull(controller.bindTurn("turn"))

        assertNull(controller.saveTurn(turn, "zh", "en", " ", "answer", 1))
        val forged = turn.copy(userId = "other-user")
        assertNull(controller.saveTurn(forged, "zh", "en", "question", "answer", 1))
        assertEquals(0, saves.started.size)
        assertEquals(conversation.sessionId, controller.state.value.sessionId)
    }

    @Test
    fun pauseKeepsConversationAndDoesNotReassignOldTurn() = runBlocking {
        val saves = BlockingSaver()
        val controller = LocalHistorySaveController(saves, CoroutineScope(Dispatchers.Default))
        val conversation = controller.startConversation("user", "solo")
        controller.pauseConversation()
        assertNull(controller.bindTurn("while-paused"))
        assertEquals(conversation, controller.resumeConversation())
        val resumed = requireNotNull(controller.bindTurn("after-resume"))
        assertEquals(conversation.sessionId, resumed.sessionId)
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(100) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
        error("condition did not become true")
    }

    private class BlockingSaver : LocalHistoryTurnSaver {
        val started = mutableSetOf<String>()
        private val waiters: MutableMap<String, CompletableDeferred<Result<Unit>>> = mutableMapOf()
        override suspend fun save(turn: CompletedTurn): String {
            synchronized(this) {
                started += turn.sourceText
                waiters.getOrPut(turn.sourceText) { CompletableDeferred<Result<Unit>>() }
            }
            return waiters[turn.sourceText]!!.await().getOrThrow().let { turn.localSessionId!! }
        }

        fun awaitStarted(source: String) {
            repeat(200) {
                if (source in started) return
                Thread.sleep(10)
            }
            error("save did not start: $source")
        }

        fun succeed(source: String) { waiters[source]!!.complete(Result.success(Unit)) }
        fun fail(source: String, error: Throwable) { waiters[source]!!.complete(Result.failure(error)) }
    }
}
