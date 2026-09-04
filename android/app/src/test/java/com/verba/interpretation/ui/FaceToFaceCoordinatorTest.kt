package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceCoordinatorTest {
    @Test fun manualPttLocksBothSidesUntilFinishedAndPlaybackDrains() {
        val coordinator = FaceToFaceCoordinator<String>()
        val press = coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")
        assertTrue(press.accepted)
        assertTrue(press.startCapture)
        assertEquals(25_000L, press.timer?.delayMillis)
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "你好")

        val blocked = coordinator.manualPress(2, FaceToFaceSide.RIGHT, "right")
        assertFalse(blocked.accepted)
        assertEquals(listOf("right"), blocked.cancelSessions)

        val release = coordinator.endManualInput()
        assertEquals(listOf("left"), release.finishSessions)
        assertTrue(release.stopCapture)
        assertEquals(FaceToFacePhase.PROCESSING, coordinator.state().phase)
        assertFalse(coordinator.manualPress(3, FaceToFaceSide.RIGHT, "blocked").accepted)

        val drain = coordinator.sessionFinished(1)
        assertTrue(drain is FaceToFaceCoordinator.PlaybackWork.Drain)
        assertFalse(coordinator.manualPress(4, FaceToFaceSide.RIGHT, "still-blocked").accepted)
        assertNull(coordinator.playbackWorkFinished(1, drained = true))
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
        assertTrue(coordinator.manualPress(5, FaceToFaceSide.RIGHT, "next").accepted)
    }

    @Test fun sidesUseExpectedLanguagesAndOppositeEars() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "你好")
        val left = coordinator.state().turns.single()
        assertEquals("zh", left.sourceLanguage)
        assertEquals("en", left.targetLanguage)
        assertEquals(PlaybackRoute.RIGHT, left.route)
        coordinator.endManualInput()
        coordinator.sessionFinished(1)
        coordinator.playbackWorkFinished(1, drained = true)

        coordinator.manualPress(2, FaceToFaceSide.RIGHT, "right")
        val right = coordinator.state().turns.last()
        assertEquals("en", right.sourceLanguage)
        assertEquals("zh", right.targetLanguage)
        assertEquals(PlaybackRoute.LEFT, right.route)
    }

    @Test fun autoRightBargeInFinishesLeftAndReleaseRestoresLeftWithoutRestartingCapture() {
        val coordinator = FaceToFaceCoordinator<String>()
        assertTrue(coordinator.setMode(FaceToFaceMode.AUTO))
        val start = coordinator.startAuto(1, "left-1")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "左侧")
        assertTrue(start.startCapture)
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)

        val right = coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right-1")
        assertEquals(listOf("left-1"), right.finishSessions)
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "right")
        assertFalse(right.startCapture)
        assertFalse(right.stopCapture)
        assertEquals(FaceToFaceSide.RIGHT, coordinator.state().activeSide)
        assertFalse(coordinator.isActiveTurn(1))
        assertTrue(coordinator.isActiveTurn(2))
        assertTrue(coordinator.sendToActive { it == "right-1" })

        val restore = coordinator.switchAuto(3, FaceToFaceSide.LEFT, "left-2")
        assertEquals(listOf("right-1"), restore.finishSessions)
        assertFalse(restore.startCapture)
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)
        assertTrue(coordinator.sendToActive { it == "left-2" })
    }

    @Test fun pauseThenResumeAutoStopsCaptureAndRestartsDefaultLanguage() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left-1")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "你好")

        val pause = coordinator.pauseAuto()
        assertTrue(pause.accepted)
        assertTrue(pause.stopCapture)
        assertEquals(listOf("left-1"), pause.finishSessions)
        assertEquals(FaceToFacePhase.PAUSED, coordinator.state().phase)
        assertFalse(coordinator.state().captureActive)

        val resume = coordinator.resumeAuto(2, "left-2")
        assertTrue(resume.accepted)
        assertTrue(resume.startCapture)
        assertEquals(FaceToFacePhase.LISTENING, coordinator.state().phase)
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)
    }

    @Test fun autoModeKeepsItsTurnOpenWithoutANormalDurationTimer() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)

        val start = coordinator.startAuto(1, "first")

        assertTrue(start.accepted)
        assertNull(start.timer)
        assertTrue(coordinator.isActiveTurn(1))
    }

    @Test fun outOfOrderFinishedAndTtsStillPlayInTurnCreationOrder() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "左侧")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "right")

        assertNull(coordinator.offerTts(2, byteArrayOf(2)))
        assertNull(coordinator.sessionFinished(2))
        val first = coordinator.offerTts(1, byteArrayOf(1)) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(1L, first.turnId)
        assertEquals(PlaybackRoute.RIGHT, first.route)
        assertArrayEquals(byteArrayOf(1), first.pcm)
        assertNull(coordinator.sessionFinished(1))
        val firstDrain = coordinator.playbackWorkFinished(1, drained = false)
        assertTrue(firstDrain is FaceToFaceCoordinator.PlaybackWork.Drain)

        val second = coordinator.playbackWorkFinished(1, drained = true) as FaceToFaceCoordinator.PlaybackWork.Chunk
        assertEquals(2L, second.turnId)
        assertEquals(PlaybackRoute.LEFT, second.route)
        assertArrayEquals(byteArrayOf(2), second.pcm)
        assertTrue(coordinator.playbackWorkFinished(2, drained = false) is FaceToFaceCoordinator.PlaybackWork.Drain)
        assertNull(coordinator.playbackWorkFinished(2, drained = true))
    }

    @Test fun stopFinishesOnlyInputWhileCancelDiscardsEverySession() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "左侧")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "right")

        val stop = coordinator.stopAuto()
        assertTrue(stop.stopCapture)
        assertEquals(listOf("right"), stop.finishSessions)
        assertTrue(stop.cancelSessions.isEmpty())
        assertEquals(FaceToFacePhase.STOPPING, coordinator.state().phase)

        val cancel = coordinator.cancelAll()
        assertTrue(cancel.stopCapture)
        assertTrue(cancel.finishSessions.isEmpty())
        assertEquals(listOf("left", "right"), cancel.cancelSessions)
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
    }

    @Test fun automaticStopBecomesEligibleForCloudCloseOnlyAfterEveryTurnAndPlaybackDrain() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "左侧")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "right")

        coordinator.stopAuto()
        assertFalse(coordinator.canCloseCloudSession())
        coordinator.sessionFinished(2)
        assertFalse(coordinator.canCloseCloudSession())
        val firstDrain = coordinator.sessionFinished(1) as FaceToFaceCoordinator.PlaybackWork.Drain
        val secondDrain = coordinator.playbackWorkFinished(firstDrain.turnId, drained = true) as FaceToFaceCoordinator.PlaybackWork.Drain
        assertFalse(coordinator.canCloseCloudSession())
        coordinator.playbackWorkFinished(secondDrain.turnId, drained = true)

        assertTrue(coordinator.canCloseCloudSession())
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
    }

    @Test fun automaticStopWithEmptyInputCanCloseCloudSessionImmediately() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")

        val stop = coordinator.stopAuto()

        assertTrue(stop.cancelSessions == listOf("left"))
        assertTrue(coordinator.canCloseCloudSession())
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
    }

    @Test fun emptyManualInputIsDiscardedWithoutLeavingATranscript() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")

        val release = coordinator.endManualInput()

        assertTrue(release.accepted)
        assertEquals(listOf("left"), release.cancelSessions)
        assertTrue(release.finishSessions.isEmpty())
        assertTrue(coordinator.state().turns.isEmpty())
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
    }

    @Test fun errorKeepsCompletedTurnsAndRecoveryClearsErrorWithoutLosingTranscript() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "左侧")

        coordinator.cancelAll("连接中断")

        assertEquals(FaceToFacePhase.ERROR, coordinator.state().phase)
        assertEquals("连接中断", coordinator.state().error)
        assertEquals(1, coordinator.state().turns.size)

        // 恢复翻译按钮走 FaceToFaceViewModel.cancel()：取消所有在途工作并清除错误，但保留已完成轮次。
        val recovery = coordinator.cancelAll()

        assertTrue(recovery.accepted)
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
        assertNull(coordinator.state().error)
        assertEquals(1, coordinator.state().turns.size)
    }

    @Test fun multipleFinalSubtitlesAggregatePerTurn() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "第一句")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "第二句")
        coordinator.updateSubtitle(1, SubtitleKind.TRANSLATION_FINAL, "First")
        coordinator.updateSubtitle(1, SubtitleKind.TRANSLATION_FINAL, "Second")

        val turn = coordinator.state().turns.single()
        assertEquals("第一句 第二句", turn.sourceText)
        assertEquals("First Second", turn.translatedText)
    }
}
