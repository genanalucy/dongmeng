package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.cloud.CloudTranslationSessionService
import com.verba.interpretation.cloud.TranslationSessionCoordinator
import com.verba.interpretation.cloud.TranslationSessionGrant
import com.verba.interpretation.cloud.UsageRecordPayload
import com.verba.interpretation.history.LocalHistoryTurnSaver
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.ui.facetoface.runContinuousLeftAction
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FaceToFaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val runtime = RecordingRuntime()
    private val cloud = RecordingCloud()
    private val playback = QueuedExecutor()
    private lateinit var vm: FaceToFaceViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = FaceToFaceViewModel(
            Application(), runtime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, playback,
        )
        vm.setMode(FaceToFaceMode.AUTO)
        vm.setView(FaceToFaceView.FACE_TO_FACE)
    }

    @After fun tearDown() {
        vm.cancel()
        playback.shutdownNow()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    private fun leftClick() = runContinuousLeftAction(vm.state.value.phase, {
        vm.microphonePermissionPolicy.request(it)
    }, vm::pauseAuto)

    private fun start() {
        leftClick()
        vm.microphonePermissionResult(true)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun accessibleTakeoverActionStartsAndEndsRightTurn() {
        start()

        assertTrue(vm.microphonePermissionPolicy.request(MicrophonePermissionAction.ContinuousTakeover))
        vm.microphonePermissionResult(true)
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(FaceToFaceSide.RIGHT, vm.state.value.activeSide)

        vm.releaseRightAuto()
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(FaceToFaceSide.LEFT, vm.state.value.activeSide)
        assertEquals(1, runtime.captureStarts)
        assertEquals(3, runtime.sockets.size)
    }

    @Test fun leftStartPauseResumeUsesPermissionIntentsAndGrantsExactlyOnce() {
        start()
        vm.microphonePermissionResult(true)
        assertEquals(1, runtime.sockets.size)
        assertEquals(1, runtime.captureStarts)
        assertEquals(FaceToFaceSide.LEFT, vm.state.value.activeSide)
        vm.microphonePermissionPolicy.request(MicrophonePermissionAction.ContinuousResume)
        leftClick()
        assertFalse(vm.microphonePermissionPolicy.hasPendingRequest())
        vm.microphonePermissionResult(true)
        assertEquals(FaceToFacePhase.PAUSED, vm.state.value.phase)
        assertEquals(1, runtime.sockets.size)
        leftClick()
        vm.microphonePermissionResult(true)
        vm.microphonePermissionResult(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, runtime.sockets.size)
        assertEquals(2, runtime.captureStarts)
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(1, cloud.opens)
    }

    @Test fun continuousEnableWaitsForPermissionBeforeLeavingManualMode() {
        vm.setMode(FaceToFaceMode.MANUAL)
        assertTrue(vm.microphonePermissionPolicy.request(MicrophonePermissionAction.ContinuousEnable))
        vm.microphonePermissionResult(false)

        assertEquals(FaceToFaceMode.MANUAL, vm.state.value.mode)
        assertEquals(FaceToFacePhase.ERROR, vm.state.value.phase)

        vm.clearError()
        assertTrue(vm.microphonePermissionPolicy.request(MicrophonePermissionAction.ContinuousEnable))
        vm.microphonePermissionResult(true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(FaceToFaceMode.AUTO, vm.state.value.mode)
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
    }

    @Test fun deniedOrClearedPermissionNeverStartsCapture() {
        leftClick()
        vm.microphonePermissionResult(false)
        vm.microphonePermissionResult(true)
        assertEquals(FaceToFacePhase.ERROR, vm.state.value.phase)
        vm.clearError()
        leftClick()
        vm.cancel()
        vm.microphonePermissionResult(true)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(runtime.sockets.isEmpty())
        assertEquals(0, cloud.opens)
    }

    @Test fun pauseAndLifecycleInvalidatePendingCloudGrant() {
        startPendingThenInvalidate(vm::pauseAuto)
        startPendingThenInvalidate(vm::cancel)
        assertEquals(2, cloud.ends)
        assertTrue(runtime.sockets.isEmpty())
        assertEquals(0, runtime.captureStarts)
    }

    private fun startPendingThenInvalidate(invalidate: () -> Unit) {
        vm.startAuto()
        invalidate()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun finishedAndDrainedRightBeforeCancelRestoresOnlyOneLeftAndClosesAfterPlayback() {
        start()
        val left = runtime.sockets[0]
        left.source("left")
        vm.pressRightAuto()
        val right = runtime.sockets[1]
        right.source("right")
        right.tts(byteArrayOf(1, 0))
        right.event(AgentEvent.Finished)
        left.event(AgentEvent.Finished)
        playback.drain()
        assertEquals(listOf(PlaybackRoute.LEFT), runtime.routes)
        assertEquals(2, runtime.drains)
        // Real capture callback must tolerate the terminal socket until pointer cancellation.
        runtime.packet?.invoke(ByteArray(2560))
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(0, cloud.ends)
        vm.cancelRightAuto()
        vm.cancelRightAuto()
        vm.releaseRightAuto()
        assertEquals(3, runtime.sockets.size)
        assertEquals(FaceToFaceSide.LEFT, vm.state.value.activeSide)
        assertEquals(3L, vm.state.value.activeTurnId)
        assertEquals(1, vm.state.value.turns.count { !it.finished })
        assertTrue(vm.state.value.turns.first { it.id == 2L }.finished)
        assertEquals(0, right.finishes)
        assertEquals(0, right.cancels)
        val restored = runtime.sockets[2]
        assertEquals("zh" to "en", restored.languages)
        restored.source("restored")
        restored.tts(byteArrayOf(2, 0))
        vm.stopAuto()
        assertEquals(1, restored.finishes)
        restored.event(AgentEvent.Finished)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, cloud.ends)
        playback.drain()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(PlaybackRoute.LEFT, PlaybackRoute.RIGHT), runtime.routes)
        assertEquals(1, cloud.ends)
        assertEquals(1, cloud.usages)
        assertEquals(FaceToFacePhase.IDLE, vm.state.value.phase)
        vm.cancelRightAuto()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(3, runtime.sockets.size)
        assertEquals(1, cloud.opens)
    }

    @Test fun rejectedRestoreCancelsNewSocketWithoutRevivingPausedCapture() {
        start()
        vm.pressRightAuto()
        runtime.onStart = vm::pauseAuto
        vm.cancelRightAuto()
        val rejected = runtime.sockets.last()
        assertEquals(1, rejected.cancels)
        assertEquals(0, rejected.finishes)
        assertEquals(FaceToFacePhase.PAUSED, vm.state.value.phase)
        assertNull(vm.state.value.activeTurnId)
        vm.cancelRightAuto()
        assertEquals(3, runtime.sockets.size)
    }

    @Test fun lifecycleStopsPlaybackClosesCloudAndIgnoresLateCallbacks() {
        start()
        val socket = runtime.sockets.single()
        socket.tts(byteArrayOf(1, 0))
        vm.cancel()
        socket.event(AgentEvent.Finished)
        playback.drain()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, socket.cancels)
        assertEquals(1, runtime.playbackStops)
        assertTrue(runtime.routes.isEmpty())
        assertEquals(1, cloud.ends)
        assertFalse(vm.state.value.captureActive)
    }
}

private class RecordingCloud : CloudTranslationSessionService {
    var opens = 0
    var ends = 0
    var usages = 0
    override fun createTranslationSession() = TranslationSessionGrant("session-${++opens}", "user", "install", "test-token")
    override fun createUsageRecord(payload: UsageRecordPayload) { usages++ }
    override fun endTranslationSession(sessionId: String) { ends++ }
}

private class RecordingRuntime : FaceToFaceRuntime {
    val sockets = mutableListOf<RecordingSocket>()
    var onStart: () -> Unit = {}
    var captureStarts = 0
    var playbackStops = 0
    var drains = 0
    var packet: ((ByteArray) -> Unit)? = null
    val routes = mutableListOf<PlaybackRoute>()
    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray) -> Unit, onFailure: (String) -> Unit) =
        RecordingSocket(onEvent, onTts) { onStart() }.also { sockets += it }
    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit): CaptureResult {
        captureStarts++
        packet = onPacket
        return CaptureResult.Started
    }
    override fun stopCapture() { packet = null }
    override fun play(pcm: ByteArray, route: PlaybackRoute): Result<Unit> { routes += route; return Result.success(Unit) }
    override fun awaitDrained(): Result<Unit> { drains++; return Result.success(Unit) }
    override fun stopPlayback() { playbackStops++ }
}

private class RecordingSocket(val event: (AgentEvent) -> Unit, val tts: (ByteArray) -> Unit, val onStart: () -> Unit) : FaceToFaceSocket {
    var finishes = 0
    var cancels = 0
    var languages: Pair<String, String>? = null
    override fun start(source: String, target: String, grant: TranslationSessionGrant): Boolean {
        languages = source to target
        onStart()
        return true
    }
    override fun sendAudio(packet: ByteArray) = true
    override fun finish() { finishes++ }
    override fun cancel() { cancels++ }
    fun source(text: String) = event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, text))
}

private class QueuedExecutor : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var stopped = false
    override fun execute(command: Runnable) { check(!stopped); tasks.addLast(command) }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    override fun shutdown() { stopped = true }
    override fun shutdownNow(): MutableList<Runnable> { stopped = true; return tasks.toMutableList().also { tasks.clear() } }
    override fun isShutdown() = stopped
    override fun isTerminated() = stopped && tasks.isEmpty()
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
}
