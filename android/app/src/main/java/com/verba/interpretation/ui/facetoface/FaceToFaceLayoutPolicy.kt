package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.faceToFacePlaybackRoute

enum class FaceToFacePanelPosition { FAR, NEAR }

data class FaceToFacePanelSpec(
    val position: FaceToFacePanelPosition,
    val side: FaceToFaceSide,
    val rotationDegrees: Float,
) {
    val isRotated: Boolean get() = rotationDegrees != 0f
}

internal fun faceToFacePanelSpecs(): List<FaceToFacePanelSpec> = listOf(
    FaceToFacePanelSpec(FaceToFacePanelPosition.FAR, FaceToFaceSide.RIGHT, 180f),
    FaceToFacePanelSpec(FaceToFacePanelPosition.NEAR, FaceToFaceSide.LEFT, 0f),
)

internal fun faceToFacePanelRotation(position: FaceToFacePanelPosition): Float =
    faceToFacePanelSpecs().first { it.position == position }.rotationDegrees

internal fun faceToFaceTargetSide(speaker: FaceToFaceSide): FaceToFaceSide = when (speaker) {
    FaceToFaceSide.LEFT -> FaceToFaceSide.RIGHT
    FaceToFaceSide.RIGHT -> FaceToFaceSide.LEFT
}

internal fun faceToFacePlaybackRouteForPolicy(speaker: FaceToFaceSide): PlaybackRoute =
    faceToFacePlaybackRoute(speaker)
