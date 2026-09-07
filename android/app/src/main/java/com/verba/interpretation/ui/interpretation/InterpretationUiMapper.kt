package com.verba.interpretation.ui.interpretation

import com.verba.interpretation.protocol.TranslationSessionEndReason
import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.TranslationLanguage

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
    val phase: SessionPhase,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val languageDirection: String,
    val sourceText: String,
    val translationText: String,
    val bubbles: List<InterpretationDisplayBubble>,
    val showMicrophoneRipple: Boolean,
    val actions: List<InterpretationAction>,
    val primaryAction: InterpretationAction?,
    val statusLabel: String,
    val errorMessage: String?,
)

object InterpretationUiMapper {
    const val SESSION_REPLACED_MESSAGE = "已在另一设备开始翻译"
    const val SESSION_ENDED_MESSAGE = "翻译会话已结束，请重新开始。"
    const val SAFE_ERROR_MESSAGE = "翻译服务暂时不可用，请重试或重新开始。"
    const val RECOVERY_ACTION_LABEL = "重新开始翻译"

    fun map(state: InterpretationUiState): InterpretationScreenModel {
        val latest = state.turns.lastOrNull()
        val sourceText = latest?.sourceText.orEmpty()
        val translationText = latest?.translatedText.orEmpty()
        val sourceLanguageName = TranslationLanguage.displayName(state.sourceLanguage)
        val targetLanguageName = TranslationLanguage.displayName(state.targetLanguage)
        return InterpretationScreenModel(
            phase = state.phase,
            sourceLanguageName = sourceLanguageName,
            targetLanguageName = targetLanguageName,
            languageDirection = "$sourceLanguageName → $targetLanguageName",
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
            primaryAction = primaryActionFor(state.phase),
            statusLabel = statusLabelFor(state.phase),
            errorMessage = if (state.phase == SessionPhase.ERROR) {
                when (state.sessionEndReason) {
                    TranslationSessionEndReason.REPLACED -> SESSION_REPLACED_MESSAGE
                    TranslationSessionEndReason.ENDED -> SESSION_ENDED_MESSAGE
                    null -> SAFE_ERROR_MESSAGE
                }
            } else {
                null
            },
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

    private fun primaryActionFor(phase: SessionPhase): InterpretationAction? = when (phase) {
        SessionPhase.IDLE -> InterpretationAction.START
        SessionPhase.STARTING -> null
        SessionPhase.RUNNING -> InterpretationAction.PAUSE
        SessionPhase.PAUSED -> InterpretationAction.RESUME
        SessionPhase.STOPPING -> null
        SessionPhase.ERROR -> InterpretationAction.RESET
    }

    private fun statusLabelFor(phase: SessionPhase): String = when (phase) {
        SessionPhase.IDLE -> ""
        SessionPhase.STARTING -> "正在连接翻译服务"
        SessionPhase.RUNNING -> "正在翻译"
        SessionPhase.PAUSED -> "已暂停"
        SessionPhase.STOPPING -> "正在结束同传"
        SessionPhase.ERROR -> "翻译未完成"
    }
}

/** 终止/错误状态只提供显式新会话入口，绝不映射为 resume。 */
internal fun interpretationActionLabel(action: InterpretationAction): String = when (action) {
    InterpretationAction.START -> "开始"
    InterpretationAction.PAUSE -> "暂停"
    InterpretationAction.RESUME -> "继续"
    InterpretationAction.RESET -> InterpretationUiMapper.RECOVERY_ACTION_LABEL
    InterpretationAction.FINISH -> error("结束同传使用独立操作按钮")
}
