package com.verba.interpretation.ui

import com.verba.interpretation.protocol.TranslationSessionEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterpretationStateMachineTest {
    @Test fun supportsStartPauseResumeFinish() {
        val reduce = InterpretationStateMachine::reduce
        assertEquals(SessionPhase.STARTING, reduce(SessionPhase.IDLE, SessionAction.Start))
        assertEquals(SessionPhase.RUNNING, reduce(SessionPhase.STARTING, SessionAction.Ready))
        assertEquals(SessionPhase.PAUSED, reduce(SessionPhase.RUNNING, SessionAction.Pause))
        assertEquals(SessionPhase.STARTING, reduce(SessionPhase.PAUSED, SessionAction.Resume))
        assertEquals(SessionPhase.STOPPING, reduce(SessionPhase.RUNNING, SessionAction.Finish))
        assertEquals(SessionPhase.STOPPING, reduce(SessionPhase.STOPPING, SessionAction.Ready))
        assertEquals(SessionPhase.IDLE, reduce(SessionPhase.STOPPING, SessionAction.Drained))
    }

    @Test fun invalidTransitionsAreIgnoredAndErrorResets() {
        assertEquals(SessionPhase.IDLE, InterpretationStateMachine.reduce(SessionPhase.IDLE, SessionAction.Pause))
        assertEquals(SessionPhase.ERROR, InterpretationStateMachine.reduce(SessionPhase.RUNNING, SessionAction.Fail))
        assertEquals(SessionPhase.IDLE, InterpretationStateMachine.reduce(SessionPhase.ERROR, SessionAction.Reset))
    }

    @Test fun terminalSessionKeepsOnlyCompletedPairsAndDiscardsIncompleteContent() {
        val terminated = InterpretationUiState(
            phase = SessionPhase.RUNNING,
            turns = listOf(
                SubtitleTurn(
                    id = 1,
                    sourceLanguage = "zh",
                    targetLanguage = "en",
                    sourceFinals = listOf("完成", "没有译文"),
                    sourcePartial = "未完成原文",
                    translationFinals = listOf("Done"),
                    translationPartial = "unfinished translation",
                ),
                SubtitleTurn(
                    id = 2,
                    sourceLanguage = "zh",
                    targetLanguage = "en",
                    sourcePartial = "只有 partial",
                ),
            ),
        ).withTerminatedSession(TranslationSessionEndReason.REPLACED)

        assertEquals(SessionPhase.ERROR, terminated.phase)
        assertEquals(TranslationSessionEndReason.REPLACED, terminated.sessionEndReason)
        assertNull(terminated.error)
        assertEquals(1, terminated.turns.size)
        assertEquals(listOf("完成"), terminated.turns.single().sourceFinals)
        assertEquals(listOf("Done"), terminated.turns.single().translationFinals)
        assertEquals("", terminated.turns.single().sourcePartial)
        assertEquals("", terminated.turns.single().translationPartial)
    }
}
