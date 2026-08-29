package com.verba.interpretation.ui.interpretation

import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SessionPhase

enum class InterpretationAction { START, PAUSE, RESUME, FINISH, RESET }

data class InterpretationCallbacks(
    val onExit: () -> Unit,
    val onStart: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onFinish: () -> Unit,
    val onReset: () -> Unit,
)

object InterpretationActionDispatcher {
    fun exit(callbacks: InterpretationCallbacks) = callbacks.onExit()

    fun dispatch(action: InterpretationAction, callbacks: InterpretationCallbacks) = when (action) {
        InterpretationAction.START -> callbacks.onStart()
        InterpretationAction.PAUSE -> callbacks.onPause()
        InterpretationAction.RESUME -> callbacks.onResume()
        InterpretationAction.FINISH -> callbacks.onFinish()
        InterpretationAction.RESET -> callbacks.onReset()
    }
}

data class InterpretationLayoutModel(
    val transcriptScrolls: Boolean,
    val actionsPinned: Boolean,
    val actionsFitViewport: Boolean,
)

object InterpretationLayoutPolicy {
    private const val HEADER_HEIGHT_DP = 72
    private const val ACTION_HEIGHT_DP = 48
    private const val ACTION_GAP_DP = 8
    private const val ACTION_PADDING_DP = 24

    fun forViewport(viewportHeightDp: Int, actionCount: Int): InterpretationLayoutModel {
        val actionAreaHeight = ACTION_PADDING_DP + actionCount * ACTION_HEIGHT_DP + (actionCount - 1).coerceAtLeast(0) * ACTION_GAP_DP
        return InterpretationLayoutModel(
            transcriptScrolls = true,
            actionsPinned = true,
            actionsFitViewport = viewportHeightDp >= HEADER_HEIGHT_DP + actionAreaHeight,
        )
    }
}

data class InterpretationScreenModel(
    val languageDirection: String,
    val sourceText: String,
    val translationText: String,
    val showMicrophoneRipple: Boolean,
    val actions: List<InterpretationAction>,
    val errorMessage: String?,
)

object InterpretationUiMapper {
    const val SAFE_ERROR_MESSAGE = "翻译服务暂时不可用，请重试或重新开始。"

    fun map(state: InterpretationUiState): InterpretationScreenModel {
        val latest = state.turns.lastOrNull()
        return InterpretationScreenModel(
            languageDirection = "${state.sourceLanguage} → ${state.targetLanguage}",
            sourceText = latest?.sourceText.orEmpty(),
            translationText = latest?.translatedText.orEmpty(),
            showMicrophoneRipple = state.phase == SessionPhase.RUNNING,
            actions = actionsFor(state.phase),
            errorMessage = if (state.phase == SessionPhase.ERROR) SAFE_ERROR_MESSAGE else null,
        )
    }

    private fun actionsFor(phase: SessionPhase): List<InterpretationAction> = when (phase) {
        SessionPhase.IDLE -> listOf(InterpretationAction.START)
        SessionPhase.STARTING -> listOf(InterpretationAction.FINISH)
        SessionPhase.RUNNING -> listOf(InterpretationAction.PAUSE, InterpretationAction.FINISH)
        SessionPhase.PAUSED -> listOf(InterpretationAction.RESUME, InterpretationAction.FINISH)
        SessionPhase.ERROR -> listOf(InterpretationAction.RESET)
        SessionPhase.STOPPING -> emptyList()
    }
}
