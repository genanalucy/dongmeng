package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn

internal enum class FaceToFaceTurnAlignment { START, END }

internal data class FaceToFacePresentation(
    val activeMic: FaceToFaceSide?,
    val listeningLabel: String,
    val timelinePlaceholder: String,
    val canChangeLanguages: Boolean,
    val isContinuous: Boolean,
    val showRecoveryAction: Boolean,
)

/** 错误恢复按钮的显式文案：点击后取消在途工作并清除错误，保留已完成的对话轮次。 */
internal const val FACE_TO_FACE_RECOVERY_ACTION_LABEL = "恢复翻译"

internal fun faceToFacePresentation(state: FaceToFaceState): FaceToFacePresentation = FaceToFacePresentation(
    activeMic = state.activeSide.takeIf {
        state.phase == FaceToFacePhase.LISTENING && state.captureActive
    },
    listeningLabel = if (state.activeSourceLanguage() == "zh") "听取中…" else "Listening…",
    timelinePlaceholder = if (state.activeSourceLanguage() == "zh") "听取中…" else "Listening…",
    canChangeLanguages = state.phase == FaceToFacePhase.IDLE && !state.captureActive,
    isContinuous = state.mode == FaceToFaceMode.AUTO,
    showRecoveryAction = state.phase == FaceToFacePhase.ERROR,
)

internal fun faceToFaceTurnAlignment(turn: FaceToFaceTurn): FaceToFaceTurnAlignment =
    if (turn.side == FaceToFaceSide.LEFT) FaceToFaceTurnAlignment.START else FaceToFaceTurnAlignment.END

private fun FaceToFaceState.activeSourceLanguage(): String = when (activeSide) {
    FaceToFaceSide.RIGHT -> rightLanguage
    FaceToFaceSide.LEFT,
    null,
    -> leftLanguage
}
