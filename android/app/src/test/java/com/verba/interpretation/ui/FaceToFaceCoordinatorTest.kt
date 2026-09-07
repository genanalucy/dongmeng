package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.protocol.TranslationSessionEndReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceToFaceCoordinatorTest {
    @Test fun rejectedRestoreIsAtomicAndReturnsNewSessionForCancellation() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        val before = coordinator.state()
        val rejected = coordinator.cancelAutoTakeover(1, "new-left")
        assertFalse(rejected.accepted)
        assertEquals(listOf("new-left"), rejected.cancelSessions)
        assertEquals(before, coordinator.state())
        assertTrue(coordinator.containsTurn(2))
        assertTrue(coordinator.sendToActive { it == "right" })
    }

    @Test fun stoppingEmptyRestoredLeftWaitsForOlderRightPlaybackBeforeCloudClose() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_FINAL, "right")
        coordinator.sessionFinished(2)
        coordinator.cancelAutoTakeover(3, "restored")
        val stop = coordinator.stopAuto()
        assertFalse(stop.closeCloudSession)
        assertFalse(coordinator.canCloseCloudSession())
        coordinator.playbackWorkFinished(2, drained = true)
        assertTrue(coordinator.canCloseCloudSession())
    }

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

    @Test fun viewDefaultsToConversationAndSwitchingStopsActiveManualInputSafely() {
        val coordinator = FaceToFaceCoordinator<String>()

        assertEquals(FaceToFaceView.CONVERSATION, coordinator.state().view)
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "你好")

        val transition = coordinator.setView(FaceToFaceView.FACE_TO_FACE)

        assertTrue(transition.accepted)
        assertEquals(listOf("left"), transition.finishSessions)
        assertTrue(transition.stopCapture)
        assertEquals(FaceToFaceView.FACE_TO_FACE, coordinator.state().view)
        assertEquals(FaceToFacePhase.PROCESSING, coordinator.state().phase)
        assertFalse(coordinator.setView(FaceToFaceView.CONVERSATION).accepted)
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

    @Test fun cancelingRightTakeoverDiscardsItAndRestoresLeftWithOneLiveTurn() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left-1")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right-1")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "cancel me")

        val cancel = coordinator.cancelAutoTakeover(3, "left-2")

        assertTrue(cancel.accepted)
        assertEquals(listOf("right-1"), cancel.cancelSessions)
        assertTrue(cancel.finishSessions.isEmpty())
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)
        assertTrue(coordinator.isActiveTurn(3))
        assertFalse(coordinator.state().turns.any { it.id == 2L })
        assertEquals(1, coordinator.state().turns.count { !it.finished })
    }

    @Test fun finishedRightTakeoverIsPreservedWhileCancelRestoresLeft() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_FINAL, "right")
        coordinator.sessionFinished(2)
        val firstDrain = coordinator.sessionFinished(1)
        assertTrue(firstDrain is FaceToFaceCoordinator.PlaybackWork.Drain)
        coordinator.playbackWorkFinished(1, drained = true)

        val cancel = coordinator.cancelAutoTakeover(3, "left-restored")

        assertTrue(cancel.accepted)
        assertTrue(cancel.cancelSessions.isEmpty())
        assertTrue(coordinator.state().turns.any { it.id == 2L && it.finished })
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)
        assertEquals(3L, coordinator.state().activeTurnId)
        assertFalse(coordinator.cancelAutoTakeover(4, "stale").accepted)
        assertTrue(coordinator.containsTurn(3))
        assertEquals(1, coordinator.state().turns.count { !it.finished })
    }

    @Test fun finishedAutoTurnIsNotFinishedAgainWhenTakeoverRestoresOrStops() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "right")
        coordinator.sessionFinished(2)

        val restore = coordinator.switchAuto(3, FaceToFaceSide.LEFT, "left-restored")

        assertTrue(restore.accepted)
        // The right socket already emitted Finished; switching back must not finish it again.
        assertTrue(restore.finishSessions.isEmpty())
    }

    @Test fun lateCancelAfterFinishedRightEntryDrainedRestoresLeftOnceAndPreservesHistory() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_FINAL, "right")
        coordinator.updateSubtitle(2, SubtitleKind.TRANSLATION_FINAL, "droite")
        coordinator.sessionFinished(2)

        val leftDrain = coordinator.sessionFinished(1) as FaceToFaceCoordinator.PlaybackWork.Drain
        val rightDrain = coordinator.playbackWorkFinished(leftDrain.turnId, drained = true)
        assertTrue(rightDrain is FaceToFaceCoordinator.PlaybackWork.Drain)
        assertNull(coordinator.playbackWorkFinished(requireNotNull(rightDrain).turnId, drained = true))
        assertFalse(coordinator.containsTurn(2))
        assertEquals(FaceToFaceSide.RIGHT, coordinator.state().activeSide)
        assertEquals(2L, coordinator.state().activeTurnId)

        val restore = coordinator.cancelAutoTakeover(3, "left-restored")

        assertTrue(restore.accepted)
        assertTrue(restore.finishSessions.isEmpty())
        assertTrue(restore.cancelSessions.isEmpty())
        assertEquals(FaceToFaceSide.LEFT, coordinator.state().activeSide)
        assertEquals(3L, coordinator.state().activeTurnId)
        assertEquals(listOf(1L, 2L, 3L), coordinator.state().turns.map { it.id })
        assertTrue(coordinator.state().turns.first { it.id == 2L }.finished)
        assertEquals(1, coordinator.state().turns.count { !it.finished })

        val stale = coordinator.cancelAutoTakeover(4, "stale")
        assertFalse(stale.accepted)
        assertEquals(listOf("stale"), stale.cancelSessions)
        assertEquals(3L, coordinator.state().activeTurnId)
        assertTrue(coordinator.containsTurn(3))
    }

    @Test fun finishedActiveTurnIsNotFinishedAgainWhenPausingOrStopping() {
        val paused = FaceToFaceCoordinator<String>()
        paused.setMode(FaceToFaceMode.AUTO)
        paused.startAuto(1, "left")
        paused.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        paused.sessionFinished(1)

        assertTrue(paused.pauseAuto().finishSessions.isEmpty())

        val stopped = FaceToFaceCoordinator<String>()
        stopped.setMode(FaceToFaceMode.AUTO)
        stopped.startAuto(1, "left")
        stopped.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "left")
        stopped.sessionFinished(1)

        assertTrue(stopped.stopAuto().finishSessions.isEmpty())
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

    @Test fun cancelledManualInputCancelsOnlyPressedTurnAndKeepsEarlierFinishedTurn() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "已完成前一轮")
        coordinator.endManualInput()
        coordinator.sessionFinished(1)
        coordinator.playbackWorkFinished(1, drained = true)

        coordinator.manualPress(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "取消这一轮")
        val cancel = coordinator.cancelManualInput()

        assertTrue(cancel.accepted)
        assertEquals(listOf("right"), cancel.cancelSessions)
        assertTrue(cancel.finishSessions.isEmpty())
        assertEquals(listOf(1L), coordinator.state().turns.map { it.id })
        assertEquals(FaceToFacePhase.IDLE, coordinator.state().phase)
    }

    @Test fun finishedBeforeCancelDoesNotFinishOrCancelSocketAgain() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "socket")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "hello")
        coordinator.sessionFinished(1)

        val cancel = coordinator.cancelManualInput(1)

        assertTrue(cancel.accepted)
        assertTrue(cancel.finishSessions.isEmpty())
        assertTrue(cancel.cancelSessions.isEmpty())
        assertEquals(FaceToFacePhase.PROCESSING, coordinator.state().phase)
        assertNull(coordinator.state().activeTurnId)
    }

    @Test fun staleAndRepeatedManualCancelAreNoOps() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(1, FaceToFaceSide.LEFT, "socket")

        assertFalse(coordinator.cancelManualInput(2).accepted)
        assertTrue(coordinator.cancelManualInput(1).accepted)
        assertFalse(coordinator.cancelManualInput(1).accepted)
        assertTrue(coordinator.state().turns.isEmpty())
    }

    @Test fun pointerReleaseFinishesButPointerCancelDiscards() {
        val released = FaceToFaceCoordinator<String>()
        released.manualPress(1, FaceToFaceSide.LEFT, "release")
        released.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "hello")
        val release = released.endManualInput()
        assertEquals(listOf("release"), release.finishSessions)
        assertTrue(released.state().turns.single().sourceText == "hello")

        val cancelled = FaceToFaceCoordinator<String>()
        cancelled.manualPress(1, FaceToFaceSide.LEFT, "cancel")
        cancelled.updateSubtitle(1, SubtitleKind.SOURCE_PARTIAL, "hello")
        val cancel = cancelled.cancelManualInput()
        assertEquals(listOf("cancel"), cancel.cancelSessions)
        assertTrue(cancelled.state().turns.isEmpty())
    }

    @Test fun stateExposesActiveTurnIdForTimeline() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.manualPress(9, FaceToFaceSide.RIGHT, "socket")
        assertEquals(9L, coordinator.state().activeTurnId)
        coordinator.endManualInput(9)
        assertNull(coordinator.state().activeTurnId)
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

    @Test fun terminalSessionCleanupIsOnceAndKeepsOnlyCompletedPairs() {
        val coordinator = FaceToFaceCoordinator<String>()
        coordinator.setMode(FaceToFaceMode.AUTO)
        coordinator.startAuto(1, "left")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "完成")
        coordinator.updateSubtitle(1, SubtitleKind.SOURCE_FINAL, "未翻译")
        coordinator.updateSubtitle(1, SubtitleKind.TRANSLATION_FINAL, "Done")
        coordinator.switchAuto(2, FaceToFaceSide.RIGHT, "right")
        coordinator.updateSubtitle(2, SubtitleKind.SOURCE_PARTIAL, "unfinished")

        val first = coordinator.terminateAll(TranslationSessionEndReason.ENDED)
        val duplicate = coordinator.terminateAll(TranslationSessionEndReason.REPLACED)

        assertTrue(first.accepted)
        assertEquals(listOf("left", "right"), first.cancelSessions)
        assertTrue(first.stopCapture)
        assertTrue(first.cancelTimer)
        assertFalse(duplicate.accepted)
        assertTrue(duplicate.cancelSessions.isEmpty())
        assertEquals(FaceToFacePhase.ERROR, coordinator.state().phase)
        assertEquals(TranslationSessionEndReason.ENDED, coordinator.state().sessionEndReason)
        assertEquals(listOf("完成"), coordinator.state().turns.single().sourceFinals)
        assertEquals(listOf("Done"), coordinator.state().turns.single().translationFinals)
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
