package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn

enum class FaceToFaceTurnAlignment { START, END }

data class FaceToFacePresentation(
    val activeMic: FaceToFaceSide?,
    val listeningLabel: String,
    val canChangeLanguages: Boolean,
    val isContinuous: Boolean,
    val turnAlignment: (FaceToFaceTurn) -> FaceToFaceTurnAlignment,
)

fun faceToFacePresentation(state: FaceToFaceState): FaceToFacePresentation = FaceToFacePresentation(
    activeMic = state.activeSide.takeIf {
        state.phase == FaceToFacePhase.LISTENING && state.captureActive
    },
    listeningLabel = if (state.leftLanguage == "zh") "听取中…" else "Listening…",
    canChangeLanguages = state.phase == FaceToFacePhase.IDLE && !state.captureActive,
    isContinuous = state.mode == FaceToFaceMode.AUTO,
    turnAlignment = { turn ->
        if (turn.side == FaceToFaceSide.LEFT) FaceToFaceTurnAlignment.START else FaceToFaceTurnAlignment.END
    },
)
