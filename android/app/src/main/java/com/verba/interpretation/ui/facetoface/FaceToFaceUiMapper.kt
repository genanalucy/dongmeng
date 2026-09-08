package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.protocol.TranslationSessionEndReason
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.display.EventBoundaryDisplay

internal enum class FaceToFaceTurnAlignment { START, END }

internal data class FaceToFacePresentation(
    val activeMic: FaceToFaceSide?,
    val listeningLabel: String,
    val timelinePlaceholder: String,
    val canChangeLanguages: Boolean,
    val isContinuous: Boolean,
    val showRecoveryAction: Boolean,
    val recoveryMessage: String?,
)

internal const val FACE_TO_FACE_SESSION_REPLACED_MESSAGE = "已在另一设备开始翻译"
internal const val FACE_TO_FACE_SESSION_ENDED_MESSAGE = "翻译会话已结束，请重新开始。"
internal const val FACE_TO_FACE_RECOVERY_ACTION_LABEL = "重新开始翻译"

internal fun earLabel(side: FaceToFaceSide): String = if (side == FaceToFaceSide.LEFT) "左耳" else "右耳"

internal fun targetEarLabel(side: FaceToFaceSide): String = if (side == FaceToFaceSide.LEFT) "右耳" else "左耳"

internal fun faceToFacePresentation(state: FaceToFaceState): FaceToFacePresentation = FaceToFacePresentation(
    activeMic = state.activeSide.takeIf {
        state.phase == FaceToFacePhase.LISTENING && state.captureActive
    },
    listeningLabel = if (state.activeSourceLanguage() == "zh") "听取中…" else "Listening…",
    timelinePlaceholder = if (state.activeSourceLanguage() == "zh") "听取中…" else "Listening…",
    canChangeLanguages = state.phase == FaceToFacePhase.IDLE && !state.captureActive,
    isContinuous = state.mode == FaceToFaceMode.AUTO,
    showRecoveryAction = state.phase == FaceToFacePhase.ERROR,
    recoveryMessage = if (state.phase == FaceToFacePhase.ERROR) {
        when (state.sessionEndReason) {
            TranslationSessionEndReason.REPLACED -> FACE_TO_FACE_SESSION_REPLACED_MESSAGE
            TranslationSessionEndReason.ENDED -> FACE_TO_FACE_SESSION_ENDED_MESSAGE
            null -> state.error
        }
    } else {
        null
    },
)

/**
 * Builds a reader-specific timeline without inferring source/translation pairs from list indexes.
 * A reader sees their own spoken source and only the translation of the other reader's source.
 */
/** Compatibility helper; face panels render the reader-specific projection below. */
internal fun faceToFacePanelTurns(state: FaceToFaceState, side: FaceToFaceSide): List<FaceToFaceTurn> = state.turns

internal fun faceToFacePanelBubbles(
    state: FaceToFaceState,
    readerSide: FaceToFaceSide,
): List<ConversationDisplayBubble> = state.turns.flatMap { turn ->
    EventBoundaryDisplay.rows(
        sourceFinals = turn.sourceFinals,
        sourcePartial = turn.sourcePartial,
        translationFinals = turn.translationFinals,
        translationPartial = turn.translationPartial,
    ).mapNotNull { row ->
        val isOwnTurn = turn.side == readerSide
        val text = if (isOwnTurn) row.sourceText else row.translationText
        text?.let {
            ConversationDisplayBubble(
                key = "${turn.id}:${row.key}:$readerSide",
                sourceText = it,
                translationText = null,
                side = turn.side,
                sourceLanguage = if (isOwnTurn) turn.sourceLanguage else turn.targetLanguage,
                targetLanguage = if (isOwnTurn) turn.targetLanguage else turn.sourceLanguage,
                alignment = faceToFaceTurnAlignment(turn),
                displayMode = ConversationDisplayMode.SINGLE_LANGUAGE,
            )
        }
    }
}

internal fun faceToFaceTurnAlignment(turn: FaceToFaceTurn): FaceToFaceTurnAlignment =
    if (turn.side == FaceToFaceSide.LEFT) FaceToFaceTurnAlignment.START else FaceToFaceTurnAlignment.END

private fun FaceToFaceState.activeSourceLanguage(): String = when (activeSide) {
    FaceToFaceSide.RIGHT -> rightLanguage
    FaceToFaceSide.LEFT,
    null,
    -> leftLanguage
}
