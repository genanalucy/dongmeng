package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.cloud.*
import com.verba.interpretation.history.CompletedTurn
import com.verba.interpretation.history.LocalHistoryTurnSaver
import com.verba.interpretation.protocol.AgentEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InterpretationHistorySaveTest {
    @Test fun liveCompletedUtterancesAreSavedWithoutWaitingForSessionFinishAndSurviveCancel() = runBlocking {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val runtime = SoloRuntime()
        val written = CopyOnWriteArrayList<CompletedTurn>()
        val cloud = object : CloudTranslationSessionService {
            override fun createTranslationSession() = TranslationSessionGrant("session", "user", "install", "token")
            override fun endTranslationSession(sessionId: String) = Unit
            override fun createUsageRecord(payload: UsageRecordPayload) = Unit
        }
        val model = InterpretationViewModel(Application(), runtime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { written += it; requireNotNull(it.localSessionId) })
        try {
            model.start()
            dispatcher.scheduler.runCurrent()
            runtime.event(AgentEvent.Ready)
            runtime.event(AgentEvent.TurnCompleted("first", "你好", "Hello"))
            runtime.event(AgentEvent.TurnCompleted("first", "你好", "Hello"))
            runtime.event(AgentEvent.TurnCompleted("second", "你好", "Hello"))
            withTimeout(3000) { while (written.size < 2) delay(10) }
            assertEquals(SessionPhase.RUNNING, model.state.value.phase)
            assertEquals(2, written.size)
            assertEquals(1, written.map { it.localSessionId }.distinct().size)
            assertNotEquals(written[0].stableTurnId, written[1].stableTurnId)
            model.cancel()
            assertEquals(2, written.size)
        } finally {
            model.cancel()
            dispatcher.scheduler.advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }
}
private class SoloRuntime : FaceToFaceRuntime {
    lateinit var event: (AgentEvent) -> Unit
    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray, Long?, String?) -> Unit, onFailure: (String) -> Unit): FaceToFaceSocket {
        event = onEvent
        return object : FaceToFaceSocket {
            override fun start(source: String, target: String, grant: TranslationSessionGrant) = true
            override fun sendAudio(packet: ByteArray) = true
            override fun finish() = Unit
            override fun cancel() = Unit
        }
    }
    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit) = CaptureResult.Started
    override fun stopCapture() = Unit
    override fun play(pcm: ByteArray, route: PlaybackRoute) = Result.success(Unit)
    override fun awaitDrained() = Result.success(Unit)
    override fun stopPlayback() = Unit
}
