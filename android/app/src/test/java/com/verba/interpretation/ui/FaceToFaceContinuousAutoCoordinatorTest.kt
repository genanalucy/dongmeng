package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceContinuousAutoCoordinatorTest {
    @Test fun continuousAzureUsesOneTransportForAlternatingLogicalTurnsAndRoutesOppositeEars() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "socket", requiresDetection = true, continuousSession = true)
        assertTrue(coordinator.resolveAutoDirection(1, "en", "zh"))
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "hello")
        coordinator.updateSubtitle(1, SubtitleKind.TRANSLATION_FINAL, "你好")
        val firstFinal = coordinator.completeAutoSegmentAfterFinalPair(1)
        assertTrue(firstFinal.finishSessions.isEmpty())
        assertTrue(firstFinal.stopCapture)
        // Packet is dropped while capture is stopped; the callback must not run.
        assertTrue(coordinator.sendToActive { error("packet must not be sent during TTS") })
        val firstTts = coordinator.offerTts(1, byteArrayOf(1)) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(1L, firstTts.turnId)
        assertEquals(1L, (coordinator.playbackWorkFinished(1, drained = false) as FaceToFaceCoordinator.PlaybackWork.Drain).turnId)
        assertTrue(coordinator.playbackWorkFinished(1, drained = true) == null)
        assertTrue(coordinator.resumeContinuousCaptureAfterDrain(1).startCapture)
        assertTrue(coordinator.beginContinuousAutoSegment(2, "socket"))
        assertTrue(coordinator.resolveAutoDirection(2, "zh", "en"))
        assertTrue(coordinator.sendToActive { true })
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_FINAL, "你好")
        coordinator.updateSubtitle(2, SubtitleKind.TRANSLATION_FINAL, "hello")
        assertTrue(coordinator.completeAutoSegmentAfterFinalPair(2).finishSessions.isEmpty())

        val second = coordinator.offerTts(2, byteArrayOf(2)) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(PlaybackRoute.RIGHT, second.route)
        assertEquals(2L, second.turnId)
        // Both logical turns share one transport; stopping finishes that transport once.
        assertEquals(listOf("socket"), coordinator.stopAuto().finishSessions)
        assertFalse(coordinator.beginContinuousAutoSegment(3, "socket"))
    }
}
