package com.verba.interpretation.ui.interpretation

import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.SubtitleTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationUiMapperTest {
    @Test fun simultaneousRunningShowsRippleAndPauseFinishActions() {
        val model = InterpretationUiMapper.map(
            InterpretationUiState(
                phase = SessionPhase.RUNNING,
                sourceLanguage = "zh",
                targetLanguage = "en",
                turns = listOf(
                    SubtitleTurn(
                        id = 1,
                        sourceLanguage = "zh",
                        targetLanguage = "en",
                        sourceFinals = listOf("你好"),
                        translationFinals = listOf("Hello"),
                    ),
                ),
            ),
        )

        assertTrue(model.showMicrophoneRipple)
        assertEquals(listOf(InterpretationAction.PAUSE, InterpretationAction.FINISH), model.actions)
        assertEquals("你好", model.sourceText)
        assertEquals("Hello", model.translationText)
    }

    @Test fun eachSessionPhaseExposesOnlyPermittedActions() {
        val expected = mapOf(
            SessionPhase.IDLE to listOf(InterpretationAction.START),
            SessionPhase.STARTING to listOf(InterpretationAction.FINISH),
            SessionPhase.RUNNING to listOf(InterpretationAction.PAUSE, InterpretationAction.FINISH),
            SessionPhase.PAUSED to listOf(InterpretationAction.RESUME, InterpretationAction.FINISH),
            SessionPhase.STOPPING to emptyList(),
            SessionPhase.ERROR to listOf(InterpretationAction.RESET),
        )

        expected.forEach { (phase, actions) ->
            assertEquals(phase.name, actions, InterpretationUiMapper.map(InterpretationUiState(phase = phase)).actions)
        }
    }

    @Test fun simultaneousErrorExposesSafeMessageOnly() {
        val model = InterpretationUiMapper.map(
            InterpretationUiState(phase = SessionPhase.ERROR, error = "token=secret dsn://backend password=hidden"),
        )

        assertEquals(InterpretationUiMapper.SAFE_ERROR_MESSAGE, model.errorMessage)
        assertFalse(model.errorMessage!!.contains("secret", ignoreCase = true))
        assertFalse(model.errorMessage.contains("token", ignoreCase = true))
    }
}
