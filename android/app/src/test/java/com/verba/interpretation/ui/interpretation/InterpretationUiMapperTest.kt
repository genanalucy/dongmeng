package com.verba.interpretation.ui.interpretation

import com.verba.interpretation.protocol.TranslationSessionEndReason
import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.SubtitleTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationUiMapperTest {
    @Test fun visualShellConstantsMatchFinalSpecs() {
        assertEquals(66f, com.verba.interpretation.ui.design.TranslationVisualTokens.TopBarHeight.value)
        assertEquals(36f, com.verba.interpretation.ui.design.TranslationVisualTokens.InterpretationDirectionRowHeight.value)
        assertEquals(102f, com.verba.interpretation.ui.design.TranslationVisualTokens.InterpretationHeaderTotalHeight.value)
        assertEquals(104f, com.verba.interpretation.ui.design.TranslationVisualTokens.PrimaryActionMinWidth.value)
        assertEquals(88f, com.verba.interpretation.ui.design.TranslationVisualTokens.SecondaryActionWidth.value)
    }

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
        assertEquals(InterpretationAction.PAUSE, model.primaryAction)
        assertEquals("正在翻译", model.statusLabel)
        assertEquals("中文 → English", model.languageDirection)
        assertEquals("你好", model.sourceText)
        assertEquals("Hello", model.translationText)
        assertEquals(listOf(InterpretationDisplayBubble("1:0", "你好", "Hello")), model.bubbles)
    }

    @Test fun latestTurnMapsFinalsByTheirSharedEventIndex() {
        val model = InterpretationUiMapper.map(
            InterpretationUiState(
                turns = listOf(
                    SubtitleTurn(
                        id = 1,
                        sourceLanguage = "zh",
                        targetLanguage = "en",
                        sourceFinals = listOf("我叫程卫东。", "啊！你打听打听去，这片谁不认识我姓陈的？"),
                        translationFinals = listOf("Bro, watch your mouth.", "Who are you?"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                InterpretationDisplayBubble("1:0", "我叫程卫东。", "Bro, watch your mouth."),
                InterpretationDisplayBubble("1:1", "啊！你打听打听去，这片谁不认识我姓陈的？", "Who are you?"),
            ),
            model.bubbles,
        )
    }

    @Test fun sourcePartialAfterFinalsIsAPendingBubble() {
        val model = InterpretationUiMapper.map(
            InterpretationUiState(
                turns = listOf(
                    SubtitleTurn(
                        id = 2,
                        sourceLanguage = "zh",
                        targetLanguage = "en",
                        sourceFinals = listOf("已确认。"),
                        sourcePartial = "还在说",
                        translationFinals = listOf("Confirmed."),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                InterpretationDisplayBubble("2:0", "已确认。", "Confirmed."),
                InterpretationDisplayBubble("2:source-partial", "还在说", null),
            ),
            model.bubbles,
        )
    }

    @Test fun eachSessionPhaseHasOnePrimaryActionOrNoActionWhileTransitioning() {
        val expected = mapOf(
            SessionPhase.IDLE to InterpretationAction.START,
            SessionPhase.STARTING to null,
            SessionPhase.RUNNING to InterpretationAction.PAUSE,
            SessionPhase.PAUSED to InterpretationAction.RESUME,
            SessionPhase.STOPPING to null,
            SessionPhase.ERROR to InterpretationAction.RESET,
        )

        expected.forEach { (phase, action) ->
            assertEquals(phase.name, action, InterpretationUiMapper.map(InterpretationUiState(phase = phase)).primaryAction)
        }
    }

    @Test fun languageDirectionUsesHumanReadableNamesAndStatusDoesNotMixErrorWithPreparation() {
        val idle = InterpretationUiMapper.map(
            InterpretationUiState(sourceLanguage = "fr", targetLanguage = "vi"),
        )
        val error = InterpretationUiMapper.map(
            InterpretationUiState(phase = SessionPhase.ERROR, sourceLanguage = "fr", targetLanguage = "vi"),
        )

        assertEquals("Français → Tiếng Việt", idle.languageDirection)
        assertEquals("", idle.statusLabel)
        assertEquals("翻译未完成", error.statusLabel)
        assertEquals("Français → Tiếng Việt", error.languageDirection)
    }

    @Test fun startingKeepsOnlyFinishForCancellationAndShowsSingleConnectionStatus() {
        val model = InterpretationUiMapper.map(InterpretationUiState(phase = SessionPhase.STARTING))
        assertEquals(listOf(InterpretationAction.FINISH), model.actions)
        assertEquals(null, model.primaryAction)
        assertEquals("正在连接翻译服务", model.statusLabel)
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

    @Test fun actionDispatcherInvokesOnlyTheExactPermittedCallback() {
        val calls = mutableListOf<String>()
        val callbacks = InterpretationCallbacks(
            onExit = { calls += "exit" },
            onStart = { calls += "start" },
            onPause = { calls += "pause" },
            onResume = { calls += "resume" },
            onFinish = { calls += "finish" },
            onReset = { calls += "reset" },
        )

        InterpretationAction.entries.forEach { action ->
            InterpretationActionDispatcher.dispatch(action, callbacks)
        }
        InterpretationActionDispatcher.exit(callbacks)

        assertEquals(listOf("start", "pause", "resume", "finish", "reset", "exit"), calls)
    }

    @Test fun simultaneousErrorExposesSafeMessageOnly() {
        val model = InterpretationUiMapper.map(
            InterpretationUiState(phase = SessionPhase.ERROR, error = "token=secret dsn://backend password=hidden"),
        )

        assertEquals(InterpretationUiMapper.SAFE_ERROR_MESSAGE, model.errorMessage)
        assertFalse(model.errorMessage!!.contains("secret", ignoreCase = true))
        assertFalse(model.errorMessage.contains("token", ignoreCase = true))
    }

    @Test fun terminalCodesMapToSafeExplicitFreshStartUx() {
        val replaced = InterpretationUiMapper.map(
            InterpretationUiState(
                phase = SessionPhase.ERROR,
                error = "malicious message",
                sessionEndReason = TranslationSessionEndReason.REPLACED,
            ),
        )
        val ended = InterpretationUiMapper.map(
            InterpretationUiState(
                phase = SessionPhase.ERROR,
                error = "malicious replacement claim",
                sessionEndReason = TranslationSessionEndReason.ENDED,
            ),
        )

        assertEquals("已在另一设备开始翻译", replaced.errorMessage)
        assertEquals("翻译会话已结束，请重新开始。", ended.errorMessage)
        assertEquals(listOf(InterpretationAction.RESET), replaced.actions)
        assertFalse(replaced.actions.contains(InterpretationAction.RESUME))
    }

    @Test fun resetActionIsLabeledAsExplicitFreshStart() {
        assertEquals("重新开始翻译", interpretationActionLabel(InterpretationAction.RESET))
        assertEquals("开始", interpretationActionLabel(InterpretationAction.START))
        assertEquals("暂停", interpretationActionLabel(InterpretationAction.PAUSE))
        assertEquals("继续", interpretationActionLabel(InterpretationAction.RESUME))
    }
}
