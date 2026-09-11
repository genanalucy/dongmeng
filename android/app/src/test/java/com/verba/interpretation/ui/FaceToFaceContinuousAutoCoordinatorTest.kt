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
        assertTrue(coordinator.completeAutoSegmentAfterFinalPair(1).finishSessions.isEmpty())
        assertTrue(coordinator.beginContinuousAutoSegment(2, "socket"))
        assertTrue(coordinator.resolveAutoDirection(2, "zh", "en"))
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_FINAL, "你好")
        coordinator.updateSubtitle(2, SubtitleKind.TRANSLATION_FINAL, "hello")
        assertTrue(coordinator.completeAutoSegmentAfterFinalPair(2).finishSessions.isEmpty())

        val first = coordinator.offerTts(1, byteArrayOf(1)) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(PlaybackRoute.LEFT, first.route)
        assertEquals(1L, first.turnId)
        val second = coordinator.offerTts(2, byteArrayOf(2))
        assertEquals(null, second)
        val routedSecond = coordinator.playbackWorkFinished(1, drained = false) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(PlaybackRoute.RIGHT, routedSecond.route)
        assertEquals(2L, routedSecond.turnId)
        // Both logical turns share one transport; stopping finishes that transport once.
        assertEquals(listOf("socket"), coordinator.stopAuto().finishSessions)
        assertFalse(coordinator.beginContinuousAutoSegment(3, "socket"))
    }
}
