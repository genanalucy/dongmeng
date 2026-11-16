package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationGuidanceTest {
    @Test fun statusDistinguishesConnectionCapturePlaybackAndRecovery() {
        assertEquals("准备就绪", conversationStatus(FaceToFaceState()))
        assertEquals("正在连接翻译服务…", conversationStatus(FaceToFaceState(connectionPending = true)))
        assertEquals("右耳方向收音中 · 译音播放中", conversationStatus(FaceToFaceState(
            phase = FaceToFacePhase.LISTENING, captureActive = true, activeSide = FaceToFaceSide.RIGHT, playbackActive = true)))
        assertEquals("译音播放中", conversationStatus(FaceToFaceState(playbackActive = true)))
        assertEquals("正在翻译，请稍候…", conversationStatus(FaceToFaceState(phase = FaceToFacePhase.PROCESSING)))
        assertEquals("已暂停收音", conversationStatus(FaceToFaceState(phase = FaceToFacePhase.PAUSED)))
        assertEquals("翻译已停止", conversationStatus(FaceToFaceState(phase = FaceToFacePhase.ERROR, connectionPending = true)))
    }

    @Test fun languageEditingReflectsBackgroundWorkAndConnection() {
        assertFalse(faceToFacePresentation(FaceToFaceState(configurationEditable = false)).canChangeLanguages)
        assertFalse(faceToFacePresentation(FaceToFaceState(connectionPending = true)).canChangeLanguages)
    }
}
