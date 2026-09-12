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
    private val effects = QueuedExecutor()
    private lateinit var vm: FaceToFaceViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = FaceToFaceViewModel(
            Application(), runtime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, playback, effects,
        )
        vm.setMode(FaceToFaceMode.AUTO)
        vm.setView(FaceToFaceView.FACE_TO_FACE)
    }

    @After fun tearDown() {
        vm.cancel()
        playback.shutdownNow()
        effects.drain()
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
        effects.drain()
    }

    @Test fun manualModeStartsNextPressBeforePreviousTurnFinishes() {
        vm.setMode(FaceToFaceMode.MANUAL)
        assertEquals(FaceToFaceMode.MANUAL, vm.state.value.mode)
        vm.manualPress(FaceToFaceSide.LEFT)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, runtime.sockets.size)
        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        runtime.sockets.single().source("第一轮")
        assertEquals("第一轮", vm.state.value.turns.single().sourceText)
        vm.manualRelease()

        assertEquals(FaceToFacePhase.PROCESSING, vm.state.value.phase)
        vm.manualPress(FaceToFaceSide.RIGHT)
        dispatcher.scheduler.runCurrent()
        effects.drain()

        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(FaceToFaceSide.RIGHT, vm.state.value.activeSide)
        assertEquals(2, runtime.sockets.size)
        assertEquals(2, runtime.captureStarts)
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
        effects.drain()
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

    @Test fun closedActiveSocketDropsTailAudioPacketWithoutFailingCapture() {
        start()
        runtime.sockets.single().sendSucceeds = false

        runtime.packet?.invoke(ByteArray(2_560))

        assertEquals(FaceToFacePhase.LISTENING, vm.state.value.phase)
        assertEquals(null, vm.state.value.error)
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
        effects.drain()
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

    @Test fun automaticStartUsesTheSocketSettingsSnapshotForCandidates() {
        runtime.autoDetection = true
        // Simulates the store changing after socket construction but before start().
        runtime.onStart = { runtime.autoDetection = false }
        vm.startAuto()
        dispatcher.scheduler.advanceUntilIdle()
        effects.drain()

        val socket = runtime.sockets.single()
        assertTrue(socket.automaticLanguageDetectionSupported)
        assertEquals(listOf("zh", "en"), socket.candidateLanguages)
    }

    @Test fun nonAzureAutomaticStartSendsNoCandidates() {
        runtime.autoDetection = false
        vm.startAuto()
        dispatcher.scheduler.advanceUntilIdle()
        effects.drain()

        assertFalse(runtime.sockets.single().automaticLanguageDetectionSupported)
        assertTrue(runtime.sockets.single().candidateLanguages.isEmpty())
    }

    @Test fun azureFinalPairsShareOneSocketAndEachDetectedSegmentRoutesItsOwnTurn() {
        val azureRuntime = RecordingRuntime().also { it.autoDetection = true }
        val azureVm = FaceToFaceViewModel(
            Application(), azureRuntime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, playback, effects,
        )
        try {
            azureVm.setMode(FaceToFaceMode.AUTO)
            azureVm.startAuto()
            dispatcher.scheduler.advanceUntilIdle()
            effects.drain()
            val first = azureRuntime.sockets.single()
            assertEquals(listOf("zh", "en"), first.candidateLanguages)
            first.event(AgentEvent.DetectedLanguage("en", segmentId = 1, targetLanguage = "zh"))
            first.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, "hello", segmentId = 1, targetLanguage = "zh"))
            first.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, "你好", segmentId = 1, targetLanguage = "zh"))
            effects.drain()
            assertEquals(0, first.finishes)
            assertEquals(0, azureRuntime.captureStops)
            assertEquals(FaceToFacePhase.LISTENING, azureVm.state.value.phase)
            azureRuntime.packet?.invoke(ByteArray(2_560))
            first.event(AgentEvent.TtsSegment(1, "zh", startsPlayback = true))
            effects.drain()
            assertEquals(1, azureRuntime.captureStops)
            first.tts(byteArrayOf(1, 0), 1, "zh")
            playback.drain()
            effects.drain()
            assertEquals(2, azureRuntime.captureStarts)
            first.event(AgentEvent.DetectedLanguage("zh", segmentId = 2, targetLanguage = "en"))
            assertEquals(FaceToFaceSide.LEFT, azureVm.state.value.activeSide)
            first.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, "你好", segmentId = 2, targetLanguage = "en"))
            first.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, "hello", segmentId = 2, targetLanguage = "en"))
            first.event(AgentEvent.TtsSegment(2, "en", startsPlayback = true))
            effects.drain()
            first.tts(byteArrayOf(2, 0), 2, "en")
            playback.drain()
            effects.drain()
            assertEquals(listOf(PlaybackRoute.LEFT, PlaybackRoute.RIGHT), azureRuntime.routes)
            assertEquals(1, azureRuntime.sockets.size)

            azureVm.stopAuto()
            effects.drain()
            assertEquals(1, first.finishes)
            first.event(AgentEvent.Finished)
            effects.drain()
            assertEquals(1, azureRuntime.sockets.size)
        } finally {
            azureVm.cancel()
        }
    }

    @Test fun azureSegmentsPersistExactlyOnceAfterTtsPreludeNotTransportFinished() {
        val azureRuntime = RecordingRuntime().also { it.autoDetection = true }
        val saved = mutableListOf<com.verba.interpretation.history.CompletedTurn>()
        val azureVm = FaceToFaceViewModel(
            Application(), azureRuntime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { saved += it; "saved" }, playback, effects,
        )
        try {
            azureVm.setMode(FaceToFaceMode.AUTO)
            azureVm.startAuto(); dispatcher.scheduler.advanceUntilIdle(); effects.drain()
            val socket = azureRuntime.sockets.single()
            socket.event(AgentEvent.DetectedLanguage("en", 1, "zh"))
            socket.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, "one", 1, "zh"))
            socket.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, "一", 1, "zh"))
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(saved.isEmpty())
            socket.event(AgentEvent.TtsSegment(1, "zh", startsPlayback = true))
            effects.drain()
            socket.event(AgentEvent.TtsSegment(1, "zh", startsPlayback = true))
            effects.drain()
            socket.tts(byteArrayOf(1, 0), 1, "zh"); playback.drain(); effects.drain()
            socket.event(AgentEvent.DetectedLanguage("zh", 2, "en"))
            socket.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, "二", 2, "en"))
            socket.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, "two", 2, "en"))
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("one" to "一"), saved.map { it.sourceText to it.translatedText })
            socket.event(AgentEvent.TtsSegment(2, "en", startsPlayback = true))
            effects.drain()
            socket.tts(byteArrayOf(2, 0), 2, "en"); playback.drain(); effects.drain()
            socket.event(AgentEvent.Finished)
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals(listOf("one" to "一", "二" to "two"), saved.map { it.sourceText to it.translatedText })
            assertEquals(listOf("en" to "zh", "zh" to "en"), saved.map { it.sourceLanguage to it.targetLanguage })
        } finally { azureVm.cancel() }
    }

    @Test fun azurePauseResumeKeepsLidRoutingForTheNewSegment() {
        val azureRuntime = RecordingRuntime().also { it.autoDetection = true }
        val azureVm = FaceToFaceViewModel(
            Application(), azureRuntime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, playback, effects,
        )
        try {
            azureVm.setMode(FaceToFaceMode.AUTO)
            azureVm.startAuto()
            dispatcher.scheduler.advanceUntilIdle()
            effects.drain()
            azureVm.pauseAuto()
            effects.drain()
            azureVm.resumeAuto()
            dispatcher.scheduler.advanceUntilIdle()
            effects.drain()

            val resumed = azureRuntime.sockets.last()
            assertEquals(2, azureRuntime.sockets.size)
            resumed.event(AgentEvent.DetectedLanguage("en", segmentId = 1, targetLanguage = "zh"))
            assertEquals(FaceToFaceSide.RIGHT, azureVm.state.value.activeSide)
            assertEquals("zh", azureVm.state.value.turns.last().targetLanguage)
            resumed.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, "hello", segmentId = 1, targetLanguage = "zh"))
            resumed.event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, "你好", segmentId = 1, targetLanguage = "zh"))
            effects.drain()
            resumed.tts(byteArrayOf(1, 0), 1, "zh")
            resumed.event(AgentEvent.Finished)
            playback.drain()
            assertEquals(PlaybackRoute.LEFT, azureRuntime.routes.last())
        } finally {
            azureVm.cancel()
        }
    }

    @Test fun rejectedRestoreCancelsNewSocketWithoutRevivingPausedCapture() {
        start()
        vm.pressRightAuto()
        runtime.onStart = vm::pauseAuto
        vm.cancelRightAuto()
        effects.drain()
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
        effects.drain()
        socket.event(AgentEvent.Finished)
        playback.drain()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, socket.cancels)
        assertEquals(1, runtime.playbackStops)
        assertTrue(runtime.routes.isEmpty())
        assertEquals(1, cloud.ends)
        assertFalse(vm.state.value.captureActive)
    }

    /** Regression: 松开手动说话键期间（stopCapture 慢，如 AudioRecord join），
     * 正在进行的 TTS 播放泵不得被 actionLock 饿死，否则真机上出现词中约 1 秒卡顿。 */
    @Test fun manualReleaseDoesNotStarveOngoingTtsPlayback() {
        val realPlayback = java.util.concurrent.Executors.newSingleThreadExecutor { thread ->
            Thread(thread, "test-face-tts").apply { isDaemon = true }
        }
        val stallRuntime = RecordingRuntime()
        val firstChunkPlaying = java.util.concurrent.CountDownLatch(1)
        val letPumpContinue = java.util.concurrent.CountDownLatch(1)
        val stopCaptureEntered = java.util.concurrent.CountDownLatch(1)
        val allowStopCapture = java.util.concurrent.CountDownLatch(1)
        val playedCount = java.util.concurrent.atomic.AtomicInteger()
        stallRuntime.onPlay = {
            if (playedCount.incrementAndGet() == 1) firstChunkPlaying.countDown()
            check(letPumpContinue.await(5, TimeUnit.SECONDS)) { "pump gate timed out" }
        }
        stallRuntime.onStopCapture = {
            stopCaptureEntered.countDown()
            check(allowStopCapture.await(5, TimeUnit.SECONDS)) { "stopCapture gate timed out" }
        }
        val stallVm = FaceToFaceViewModel(
            Application(), stallRuntime,
            TranslationSessionCoordinator(cloud, CoroutineScope(dispatcher), { 0L }, dispatcher),
            LocalHistoryTurnSaver { "saved" }, realPlayback,
        )
        try {
            stallVm.setMode(FaceToFaceMode.MANUAL)
            stallVm.manualPress(FaceToFaceSide.LEFT)
            dispatcher.scheduler.runCurrent()
            val socket = stallRuntime.sockets.single()
            socket.source("going to school")
            socket.tts(byteArrayOf(1))
            check(firstChunkPlaying.await(5, TimeUnit.SECONDS)) { "first chunk never played" }
            socket.tts(byteArrayOf(2))

            val release = Thread { stallVm.manualRelease() }
            release.start()
            check(stopCaptureEntered.await(5, TimeUnit.SECONDS)) { "stopCapture never entered" }
            letPumpContinue.countDown()

            val secondChunkInTime = awaitTrue(250) { playedCount.get() >= 2 } != null
            allowStopCapture.countDown()
            release.join(5_000)
            assertTrue("松开手动说话键时 stopCapture 阻塞了 TTS 播放泵，产生约 1 秒卡顿", secondChunkInTime)
            assertTrue(awaitTrue(5_000) { socket.finishes == 1 } != null)
            assertTrue(awaitTrue(5_000) { playedCount.get() >= 2 } != null)
        } finally {
            allowStopCapture.countDown()
            stallVm.cancel()
            realPlayback.shutdownNow()
        }
    }

    private fun awaitTrue(timeoutMillis: Long, probe: () -> Boolean): Any? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (true) {
            if (probe()) return Unit
            if (System.nanoTime() >= deadline) return null
            Thread.sleep(10)
        }
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
    var captureStops = 0
    var autoDetection = false
    var packet: ((ByteArray) -> Unit)? = null
    val routes = mutableListOf<PlaybackRoute>()
    var onPlay: (ByteArray) -> Unit = {}
    var onStopCapture: () -> Unit = { packet = null }
    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray, Long?, String?) -> Unit, onFailure: (String) -> Unit) =
        RecordingSocket(onEvent, onTts, autoDetection) { onStart() }.also { sockets += it }
    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit): CaptureResult {
        captureStarts++
        packet = onPacket
        return CaptureResult.Started
    }
    override fun stopCapture() { captureStops++; onStopCapture() }
    override fun play(pcm: ByteArray, route: PlaybackRoute): Result<Unit> { routes += route; onPlay(pcm); return Result.success(Unit) }
    override fun awaitDrained(): Result<Unit> { drains++; return Result.success(Unit) }
    override fun stopPlayback() { playbackStops++ }
}

private class RecordingSocket(
    val event: (AgentEvent) -> Unit,
    private val onTts: (ByteArray, Long?, String?) -> Unit,
    override val automaticLanguageDetectionSupported: Boolean,
    val onStart: () -> Unit,
) : FaceToFaceSocket {
    var finishes = 0
    var cancels = 0
    var languages: Pair<String, String>? = null
    var candidateLanguages: List<String> = emptyList()
    var sendSucceeds = true
    override fun start(source: String, target: String, grant: TranslationSessionGrant, candidateLanguages: List<String>): Boolean {
        languages = source to target
        this.candidateLanguages = candidateLanguages
        onStart()
        return true
    }
    override fun sendAudio(packet: ByteArray) = sendSucceeds
    override fun finish() { finishes++ }
    override fun cancel() { cancels++ }
    fun source(text: String) = event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, text))
    fun translation(text: String) = event(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.TRANSLATION_FINAL, text))
    fun tts(pcm: ByteArray, segmentId: Long? = null, targetLanguage: String? = null) = onTts(pcm, segmentId, targetLanguage)
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
