package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceUiMapperTest {
    @Test
    fun continuousRightPressAndReleaseMapToExpectedActiveMic() {
        val base = FaceToFaceState(
            mode = FaceToFaceMode.AUTO,
            phase = FaceToFacePhase.LISTENING,
            captureActive = true,
        )

        assertEquals(FaceToFaceSide.LEFT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.LEFT)).activeMic)
        assertEquals(FaceToFaceSide.RIGHT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.RIGHT)).activeMic)
    }

    @Test
    fun manualProcessingHasNoRippleAndLocksLanguageChanges() {
        val presentation = faceToFacePresentation(
            FaceToFaceState(
                mode = FaceToFaceMode.MANUAL,
                phase = FaceToFacePhase.PROCESSING,
                captureActive = false,
            ),
        )

        assertNull(presentation.activeMic)
        assertFalse(presentation.canChangeLanguages)
        assertFalse(presentation.isContinuous)
    }

    @Test
    fun turnAlignmentAndPlaybackRouteFollowTurnSide() {
        val left = FaceToFaceTurn(1L, FaceToFaceSide.LEFT, "zh", "en", PlaybackRoute.RIGHT)
        val right = FaceToFaceTurn(2L, FaceToFaceSide.RIGHT, "en", "zh", PlaybackRoute.LEFT)

        assertEquals(FaceToFaceTurnAlignment.START, faceToFacePresentation(FaceToFaceState()).turnAlignment(left))
        assertEquals(FaceToFaceTurnAlignment.END, faceToFacePresentation(FaceToFaceState()).turnAlignment(right))
    }

    @Test
    fun pausedContinuousModeHasNoRipple() {
        val presentation = faceToFacePresentation(
            FaceToFaceState(
                mode = FaceToFaceMode.AUTO,
                phase = FaceToFacePhase.PAUSED,
                captureActive = false,
            ),
        )

        assertNull(presentation.activeMic)
        assertTrue(presentation.isContinuous)
    }

    @Test
    fun idlePresentationAllowsLanguageChangesAndLocalizesListeningLabel() {
        val chinese = faceToFacePresentation(FaceToFaceState(leftLanguage = "zh"))
        val english = faceToFacePresentation(FaceToFaceState(leftLanguage = "en"))

        assertTrue(chinese.canChangeLanguages)
        assertEquals("听取中…", chinese.listeningLabel)
        assertEquals("Listening…", english.listeningLabel)
    }
}
