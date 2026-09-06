package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceLayoutPolicyTest {
    @Test
    fun farPanelIsRightSideAndRotatedWhileNearPanelIsLeftSideAndUpright() {
        val panels = faceToFacePanelSpecs()
        assertEquals(listOf(FaceToFacePanelPosition.FAR, FaceToFacePanelPosition.NEAR), panels.map { it.position })
        assertEquals(FaceToFaceSide.RIGHT, panels[0].side)
        assertEquals(180f, panels[0].rotationDegrees)
        assertTrue(panels[0].isRotated)
        assertEquals(FaceToFaceSide.LEFT, panels[1].side)
        assertEquals(0f, panels[1].rotationDegrees)
        assertFalse(panels[1].isRotated)
    }

    @Test
    fun visualRotationDoesNotChangeDirectionalAudioRoute() {
        assertEquals(FaceToFaceSide.RIGHT, faceToFaceTargetSide(FaceToFaceSide.LEFT))
        assertEquals(FaceToFaceSide.LEFT, faceToFaceTargetSide(FaceToFaceSide.RIGHT))
        assertEquals(PlaybackRoute.RIGHT, faceToFacePlaybackRouteForPolicy(FaceToFaceSide.LEFT))
        assertEquals(PlaybackRoute.LEFT, faceToFacePlaybackRouteForPolicy(FaceToFaceSide.RIGHT))
    }

    @Test
    fun panelRotationIsStableForAccessibilityAndLayoutTests() {
        assertEquals(180f, faceToFacePanelRotation(FaceToFacePanelPosition.FAR))
        assertEquals(0f, faceToFacePanelRotation(FaceToFacePanelPosition.NEAR))
    }
}
