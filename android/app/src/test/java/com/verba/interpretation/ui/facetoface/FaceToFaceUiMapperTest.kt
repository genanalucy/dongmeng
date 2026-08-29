package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceCoordinator
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceUiMapperTest {
    @Test
    fun leftTurnsMapToStartAndRightTurnsMapToEnd() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1L, FaceToFaceSide.LEFT, "left")
        val leftTurn = coordinator.state().turns.single()
        coordinator.cancelAll()
        coordinator.manualPress(2L, FaceToFaceSide.RIGHT, "right")
        val rightTurn = coordinator.state().turns.last()

        assertEquals(FaceToFaceTurnAlignment.START, faceToFaceTurnAlignment(leftTurn))
        assertEquals(FaceToFaceTurnAlignment.END, faceToFaceTurnAlignment(rightTurn))
    }

    @Test
    fun manualListeningShowsOnlyActiveSideRippleAndLocalizedPlaceholder() {
        val base = FaceToFaceState(
            mode = FaceToFaceMode.MANUAL,
            phase = FaceToFacePhase.LISTENING,
            captureActive = true,
            leftLanguage = "zh",
            rightLanguage = "en",
        )

        val left = faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.LEFT))
        val right = faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.RIGHT))

        assertEquals(FaceToFaceSide.LEFT, left.activeMic)
        assertEquals("听取中…", left.timelinePlaceholder)
        assertEquals(FaceToFaceSide.RIGHT, right.activeMic)
        assertEquals("Listening…", right.timelinePlaceholder)
    }

    @Test
    fun autoRightPressMapsRippleRightAndReleaseMapsItBackLeft() {
        val base = FaceToFaceState(
            mode = FaceToFaceMode.AUTO,
            phase = FaceToFacePhase.LISTENING,
            captureActive = true,
            leftLanguage = "zh",
            rightLanguage = "en",
        )

        assertEquals(FaceToFaceSide.RIGHT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.RIGHT)).activeMic)
        assertEquals(FaceToFaceSide.LEFT, faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.LEFT)).activeMic)
    }

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
    fun turnAlignmentAndPlaybackRouteFollowTurnsProducedByCoordinator() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1L, FaceToFaceSide.LEFT, "left")
        val left = coordinator.state().turns.single()
        coordinator.cancelAll()
        coordinator.manualPress(2L, FaceToFaceSide.RIGHT, "right")
        val right = coordinator.state().turns.last()

        assertEquals(PlaybackRoute.RIGHT, left.route)
        assertEquals(PlaybackRoute.LEFT, right.route)
        assertEquals(FaceToFaceTurnAlignment.START, faceToFaceTurnAlignment(left))
        assertEquals(FaceToFaceTurnAlignment.END, faceToFaceTurnAlignment(right))
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
    fun activeManualSideSelectsListeningLabelFromItsSourceLanguage() {
        val base = FaceToFaceState(
            mode = FaceToFaceMode.MANUAL,
            phase = FaceToFacePhase.LISTENING,
            captureActive = true,
            leftLanguage = "en",
            rightLanguage = "zh",
        )

        assertEquals("Listening…", faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.LEFT)).listeningLabel)
        assertEquals("听取中…", faceToFacePresentation(base.copy(activeSide = FaceToFaceSide.RIGHT)).listeningLabel)
    }

    @Test
    fun autoRightPressAndReleaseUseRightLanguageAndClearActiveMic() {
        val listening = FaceToFaceState(
            mode = FaceToFaceMode.AUTO,
            phase = FaceToFacePhase.LISTENING,
            captureActive = true,
            leftLanguage = "zh",
            rightLanguage = "en",
            activeSide = FaceToFaceSide.RIGHT,
        )

        assertEquals(FaceToFaceSide.RIGHT, faceToFacePresentation(listening).activeMic)
        assertEquals("Listening…", faceToFacePresentation(listening).listeningLabel)
        assertNull(
            faceToFacePresentation(
                listening.copy(phase = FaceToFacePhase.PAUSED, captureActive = false, activeSide = null),
            ).activeMic,
        )
    }
}
