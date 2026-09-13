package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.cloud.CloudTranslationSessionService
import com.verba.interpretation.cloud.TranslationSessionCoordinator
import com.verba.interpretation.cloud.TranslationSessionGrant
import com.verba.interpretation.cloud.UsageRecordPayload
import com.verba.interpretation.diagnostics.DiagnosticEntry
import com.verba.interpretation.diagnostics.DiagnosticLogger
import com.verba.interpretation.diagnostics.FaceAction
import com.verba.interpretation.diagnostics.MicrophoneOutcome
import com.verba.interpretation.diagnostics.SocketFailure
import com.verba.interpretation.diagnostics.SocketLifecycle
import com.verba.interpretation.history.LocalHistoryTurnSaver
import com.verba.interpretation.protocol.AgentEvent
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaceToFaceDiagnosticPathTest {
    @Test fun autoStartAndAgentErrorUseSafeDiagnosticMethods() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val logger = RecordingDiagnosticLogger()
        val runtime = DiagnosticRuntime()
        val vm = FaceToFaceViewModel(
            Application(), runtime,
            TranslationSessionCoordinator(DiagnosticCloud(), CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, ImmediateExecutor(), ImmediateExecutor(), logger,
        )
        vm.setMode(FaceToFaceMode.AUTO)
        vm.startAuto()
        testScheduler.advanceUntilIdle()
        runtime.socket.event(AgentEvent.Error("AZURE_UNAVAILABLE", "Bearer confidential subtitle"))

        assertTrue(FaceAction.START_AUTO in logger.actions)
        assertTrue(logger.events.any { it == "ERROR:AZURE_UNAVAILABLE" })
        assertTrue(logger.events.none { it.contains("confidential") })
    }
}

private class RecordingDiagnosticLogger : DiagnosticLogger {
    val actions = mutableListOf<FaceAction>()
    val events = mutableListOf<String>()
    override fun faceAction(action: FaceAction) { actions += action }
    override fun state(state: FaceToFaceState) = Unit
    override fun runtime(provider: com.verba.interpretation.protocol.TranslationProvider, automaticSupported: Boolean) = Unit
    override fun socketStart(provider: com.verba.interpretation.protocol.TranslationProvider, automatic: Boolean, sourceLanguage: String, targetLanguage: String) = Unit
    override fun agentEvent(event: AgentEvent) { events += if (event is AgentEvent.Error) "ERROR:${event.code}" else event::class.simpleName.orEmpty() }
    override fun socketLifecycle(stage: SocketLifecycle) = Unit
    override fun socketFailure(reason: SocketFailure) = Unit
    override fun microphone(outcome: MicrophoneOutcome) = Unit
    override fun entries(): List<DiagnosticEntry> = emptyList()
    override fun clear() = Unit
}

private class DiagnosticCloud : CloudTranslationSessionService {
    override fun createTranslationSession() = TranslationSessionGrant("session", "user", "install", "token")
    override fun createUsageRecord(payload: UsageRecordPayload) = Unit
    override fun endTranslationSession(sessionId: String) = Unit
}

private class DiagnosticRuntime : FaceToFaceRuntime {
    lateinit var socket: DiagnosticSocket
    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray, Long?, String?) -> Unit, onFailure: (String) -> Unit) = DiagnosticSocket(onEvent).also { socket = it }
    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit) = CaptureResult.Started
    override fun stopCapture() = Unit
    override fun play(pcm: ByteArray, route: PlaybackRoute) = Result.success(Unit)
    override fun awaitDrained() = Result.success(Unit)
    override fun stopPlayback() = Unit
}

private class DiagnosticSocket(val event: (AgentEvent) -> Unit) : FaceToFaceSocket {
    override fun start(source: String, target: String, grant: TranslationSessionGrant, candidateLanguages: List<String>) = true
    override fun sendAudio(packet: ByteArray) = true
    override fun finish() = Unit
    override fun cancel() = Unit
}

private class ImmediateExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
