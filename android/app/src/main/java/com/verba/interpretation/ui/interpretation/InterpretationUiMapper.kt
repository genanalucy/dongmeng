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

data class InterpretationScreenModel(
    val languageDirection: String,
    val sourceText: String,
    val translationText: String,
    val bubbles: List<InterpretationDisplayBubble>,
    val showMicrophoneRipple: Boolean,
    val actions: List<InterpretationAction>,
    val errorMessage: String?,
)

object InterpretationUiMapper {
    const val SAFE_ERROR_MESSAGE = "翻译服务暂时不可用，请重试或重新开始。"
    const val RECOVERY_ACTION_LABEL = "恢复翻译"

    fun map(state: InterpretationUiState): InterpretationScreenModel {
        val latest = state.turns.lastOrNull()
        val sourceText = latest?.sourceText.orEmpty()
        val translationText = latest?.translatedText.orEmpty()
        return InterpretationScreenModel(
            languageDirection = "${state.sourceLanguage} → ${state.targetLanguage}",
            sourceText = sourceText,
            translationText = translationText,
            bubbles = latest?.let { turn ->
                InterpretationDisplayBubble.map(
                    turnId = turn.id,
                    sourceFinals = turn.sourceFinals,
                    sourcePartial = turn.sourcePartial,
                    translationFinals = turn.translationFinals,
                    translationPartial = turn.translationPartial,
                )
            }.orEmpty(),
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

/** 错误恢复按钮的显式文案：点击后取消在途工作并清除错误，保留已完成的字幕轮次。 */
internal fun interpretationActionLabel(action: InterpretationAction): String = when (action) {
    InterpretationAction.START -> "开始"
    InterpretationAction.PAUSE -> "暂停"
    InterpretationAction.RESUME -> "继续"
    InterpretationAction.RESET -> InterpretationUiMapper.RECOVERY_ACTION_LABEL
    InterpretationAction.FINISH -> error("结束同传使用独立操作按钮")
}
