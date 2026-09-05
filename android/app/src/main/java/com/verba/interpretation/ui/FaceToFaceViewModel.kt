package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.CloudSessionFailureCode
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import com.verba.interpretation.cloud.TranslationSessionCoordinator
import com.verba.interpretation.cloud.TranslationSessionCoordinator.OpenHandle
import com.verba.interpretation.cloud.TranslationSessionGrant
import com.verba.interpretation.audio.MicrophoneCapture
import com.verba.interpretation.audio.TtsPlayer
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.AgentSocket
import com.verba.interpretation.protocol.EndpointSettings
import com.verba.interpretation.history.CompletedTurn
import com.verba.interpretation.history.LocalHistoryRepository
import com.verba.interpretation.protocol.TranslationSessionEndReason
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

class FaceToFaceViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = FaceToFaceCoordinator<AgentSocket>()
    private val mutableState = MutableStateFlow(coordinator.state())
    val state: StateFlow<FaceToFaceState> = mutableState.asStateFlow()
    private val microphone = MicrophoneCapture(application)
    private val endpointSettings = EndpointSettings(application)
    private val player = TtsPlayer()
    private val cloudSessions = TranslationSessionCoordinator(
        CloudApi(CloudEndpointSettings(application), KeystoreTokenStore(application), SharedPreferencesInstallationIdStore(application)),
        viewModelScope,
    )
    private val mutableCloudSessionCloseFailure = MutableStateFlow<CloudSessionFailureCode?>(null)
    val cloudSessionCloseFailure: StateFlow<CloudSessionFailureCode?> = mutableCloudSessionCloseFailure.asStateFlow()
    private val playbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "verba-face-tts").apply { isDaemon = true }
    }
    private val actionLock = Any()
    private val history = LocalHistoryRepository.create(application)
    private var localHistorySessionId: String? = null
    private val historyCaptureMutex = Mutex()
    private val playbackGeneration = AtomicLong()
    private var timerJob: Job? = null
    private var cloudGrant: TranslationSessionGrant? = null
    private var pendingGrantOpen: OpenHandle? = null
    private var operationGeneration = 0L
    private var nextTurnId = 1L

    init {
        observeFaceToFaceCloudSessionFailures(
            cloudSessions.endFailures,
            viewModelScope,
            mutableCloudSessionCloseFailure,
        )
    }

    fun setMode(mode: FaceToFaceMode) = synchronized(actionLock) {
        if (coordinator.setMode(mode)) publishState()
    }

    fun setLanguages(leftLanguage: String, rightLanguage: String) = synchronized(actionLock) {
        if (coordinator.setLanguages(leftLanguage, rightLanguage)) publishState()
    }

    fun manualPress(side: FaceToFaceSide) = startWithCloudGrant(
        side = side,
        canStart = { coordinator.state().mode == FaceToFaceMode.MANUAL && coordinator.state().phase == FaceToFacePhase.IDLE },
    ) { created -> applyTransition(coordinator.manualPress(created.turnId, side, created.socket)) }

    fun manualRelease() = synchronized(actionLock) {
        invalidatePendingGrantOpen()
        val transition = coordinator.endManualInput()
        applyTransition(transition)
        if (transition.accepted && transition.cancelSessions.isNotEmpty()) endCloudSession()
    }

    fun startAuto() = startWithCloudGrant(
        side = FaceToFaceSide.LEFT,
        canStart = { coordinator.state().mode == FaceToFaceMode.AUTO && coordinator.state().phase == FaceToFacePhase.IDLE },
    ) { created -> applyTransition(coordinator.startAuto(created.turnId, created.socket)) }

    fun pressRightAuto() = switchAuto(FaceToFaceSide.RIGHT)

    fun releaseRightAuto() = switchAuto(FaceToFaceSide.LEFT)

    fun pauseAuto() = synchronized(actionLock) {
        applyTransition(coordinator.pauseAuto())
    }

    fun resumeAuto() = startWithCloudGrant(
        side = FaceToFaceSide.LEFT,
        canStart = { coordinator.state().mode == FaceToFaceMode.AUTO && coordinator.state().phase == FaceToFacePhase.PAUSED },
    ) { created -> applyTransition(coordinator.resumeAuto(created.turnId, created.socket)) }

    fun stopAuto() = synchronized(actionLock) {
        invalidatePendingGrantOpen()
        applyTransition(coordinator.stopAuto())
        closeCloudSessionIfDrained()
    }

    fun microphonePermissionDenied() = fail("未授予麦克风权限。")

    fun clearError() = synchronized(actionLock) {
        coordinator.clearError()
        publishState()
    }

    /** Navigation, ON_STOP and fatal errors discard all background work instead of draining it. */
    fun cancel() = synchronized(actionLock) {
        invalidatePendingGrantOpen()
        playbackGeneration.incrementAndGet()
        applyTransition(coordinator.cancelAll())
        endCloudSession()
        player.stop()
    }

    private fun switchAuto(side: FaceToFaceSide) = startWithCloudGrant(
        side = side,
        canStart = {
            val snapshot = coordinator.state()
            snapshot.mode == FaceToFaceMode.AUTO && snapshot.phase == FaceToFacePhase.LISTENING &&
                snapshot.captureActive && snapshot.activeSide != side
        },
    ) { created -> applyTransition(coordinator.switchAuto(created.turnId, side, created.socket)) }

    private data class CreatedSession(val turnId: Long, val side: FaceToFaceSide, val socket: AgentSocket)

    private fun startWithCloudGrant(
        side: FaceToFaceSide,
        canStart: () -> Boolean,
        onCreated: (CreatedSession) -> Unit,
    ) = synchronized(actionLock) {
        val existing = cloudGrant
        if (existing != null) {
            if (canStart()) createAndStart(side, existing, onCreated)
            return
        }
        if (pendingGrantOpen != null) return
        val generation = operationGeneration
        pendingGrantOpen = cloudSessions.open(
            onGranted = { grant -> synchronized(actionLock) {
                pendingGrantOpen = null
                if (generation != operationGeneration || !canStart()) {
                    cloudSessions.end(grant.sessionId)
                    return@synchronized
                }
                cloudGrant = grant
                createAndStart(side, grant, onCreated)
            } },
            onFailure = { message -> synchronized(actionLock) {
                pendingGrantOpen = null
                if (generation == operationGeneration) fail(message)
            } },
        )
    }

    private fun createAndStart(side: FaceToFaceSide, grant: TranslationSessionGrant, onCreated: (CreatedSession) -> Unit) {
        val created = createSession(side)
        if (startSocket(created, grant)) onCreated(created) else created.socket.cancel()
    }

    private fun createSession(side: FaceToFaceSide): CreatedSession {
        val turnId = nextTurnId++
        val socket = AgentSocket(
            endpointSettings = endpointSettings,
            onEvent = { event -> synchronized(actionLock) { handleEvent(turnId, event) } },
            onTts = { pcm -> synchronized(actionLock) { queuePlayback(coordinator.offerTts(turnId, pcm)) } },
            onFailure = { message -> synchronized(actionLock) { handleSessionFailure(turnId, message) } },
        )
        return CreatedSession(turnId, side, socket)
    }

    private fun startSocket(created: CreatedSession, grant: TranslationSessionGrant): Boolean {
        val state = coordinator.state()
        val source = if (created.side == FaceToFaceSide.LEFT) state.leftLanguage else state.rightLanguage
        val target = if (created.side == FaceToFaceSide.LEFT) state.rightLanguage else state.leftLanguage
        if (created.socket.start(source, target, grant)) return true
        fail("无法创建翻译会话。")
        return false
    }

    private fun applyTransition(transition: FaceToFaceCoordinator.Transition<AgentSocket>) {
        if (transition.cancelTimer) {
            timerJob?.cancel()
            timerJob = null
        }
        transition.cancelSessions.forEach { it.cancel() }
        transition.finishSessions.forEach { it.finish() }
        if (transition.stopCapture) microphone.stop()
        if (transition.startCapture) startCapture()
        transition.timer?.let(::scheduleTimer)
        if (transition.closeCloudSession) endCloudSession()
        publishState()
    }

    private fun startCapture() {
        when (val result = microphone.start(
            onPacket = { packet ->
                if (!coordinator.sendToActive { it.sendAudio(packet) }) fail("音频包无法发送，连接尚未就绪或已断开。")
            },
            onError = ::fail,
            onLevel = { level ->
                synchronized(actionLock) {
                    if (coordinator.updateCaptureLevel(level)) publishState()
                }
            },
        )) {
            CaptureResult.Started -> Unit
            CaptureResult.AlreadyRunning -> fail("麦克风已在录音。")
            CaptureResult.Stopped -> fail("麦克风未启动。")
            is CaptureResult.Error -> fail(result.message)
        }
    }

    private fun scheduleTimer(intent: FaceToFaceCoordinator.TimerIntent) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            delay(intent.delayMillis)
            synchronized(actionLock) { applyTransition(coordinator.endManualInput(intent.turnId)) }
        }
    }

    private fun handleEvent(turnId: Long, event: AgentEvent) {
        if (!coordinator.containsTurn(turnId)) return
        when (event) {
            AgentEvent.Ready -> Unit
            AgentEvent.Finished -> {
                captureCompletedTurn(turnId)
                queuePlayback(coordinator.sessionFinished(turnId))
                closeCloudSessionIfDrained()
                publishState()
            }
            is AgentEvent.Subtitle -> {
                coordinator.updateSubtitle(turnId, event.kind.toSubtitleKind(), event.text)
                publishState()
            }
            is AgentEvent.SessionTerminated -> terminateSession(event.reason)
            is AgentEvent.Error -> handleSessionFailure(turnId, "${event.code}: ${event.message}")
        }
    }

    private fun captureCompletedTurn(turnId: Long) {
        val turn = coordinator.state().turns.firstOrNull { it.id == turnId } ?: return
        val sourceText = turn.sourceFinals.joinToString(" ").trim()
        val translatedText = turn.translationFinals.joinToString(" ").trim()
        if (sourceText.isBlank() || translatedText.isBlank()) return
        val userId = cloudGrant?.userId ?: return
        viewModelScope.launch {
            historyCaptureMutex.withLock {
                localHistorySessionId = history.recordCompletedTurn(
                    CompletedTurn(
                        userId = userId,
                        localSessionId = localHistorySessionId,
                        mode = "face_to_face",
                        sourceLanguage = turn.sourceLanguage,
                        targetLanguage = turn.targetLanguage,
                        sourceText = sourceText,
                        translatedText = translatedText,
                        completedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private fun handleSessionFailure(turnId: Long, message: String) {
        if (coordinator.isActiveTurn(turnId)) {
            fail(message)
            return
        }
        queuePlayback(coordinator.sessionFinished(turnId))
        closeCloudSessionIfDrained()
        publishState()
    }

    private fun queuePlayback(first: FaceToFaceCoordinator.PlaybackWork?) {
        if (first == null) return
        val generation = playbackGeneration.get()
        playbackExecutor.execute {
            var work: FaceToFaceCoordinator.PlaybackWork? = first
            while (work != null && playbackGeneration.get() == generation) {
                val current = work ?: break
                val result = when (current) {
                    is FaceToFaceCoordinator.PlaybackWork.Chunk -> player.play(current.pcm, current.route)
                    is FaceToFaceCoordinator.PlaybackWork.Drain -> player.awaitDrained()
                }
                if (result.isFailure) {
                    fail(result.exceptionOrNull()?.message ?: "TTS 播放失败。")
                    return@execute
                }
                val drained = current is FaceToFaceCoordinator.PlaybackWork.Drain
                work = coordinator.playbackWorkFinished(current.turnId, drained)
                closeCloudSessionIfDrained()
                publishState()
            }
        }
    }

    private fun terminateSession(reason: TranslationSessionEndReason) = synchronized(actionLock) {
        val transition = coordinator.terminateAll(reason)
        if (!transition.accepted) return
        invalidatePendingGrantOpen()
        playbackGeneration.incrementAndGet()
        applyTransition(transition)
        endCloudSession()
        player.stop()
    }

    private fun fail(message: String) = synchronized(actionLock) {
        if (coordinator.state().sessionEndReason != null) return
        invalidatePendingGrantOpen()
        playbackGeneration.incrementAndGet()
        applyTransition(coordinator.cancelAll(message))
        endCloudSession()
        player.stop()
    }

    private fun closeCloudSessionIfDrained() {
        if (coordinator.canCloseCloudSession()) endCloudSession()
    }

    private fun invalidatePendingGrantOpen() {
        operationGeneration += 1
        pendingGrantOpen?.cancel()
        pendingGrantOpen = null
    }

    private fun endCloudSession() {
        val sessionId = cloudGrant?.sessionId
        cloudGrant = null
        cloudSessions.end(sessionId)
    }

    private fun publishState() {
        mutableState.value = coordinator.state()
    }

    override fun onCleared() {
        cancel()
        playbackExecutor.shutdownNow()
        super.onCleared()
    }
}

private fun AgentEvent.Subtitle.Kind.toSubtitleKind(): SubtitleKind = when (this) {
    AgentEvent.Subtitle.Kind.SOURCE_PARTIAL -> SubtitleKind.SOURCE_PARTIAL
    AgentEvent.Subtitle.Kind.SOURCE_FINAL -> SubtitleKind.SOURCE_FINAL
    AgentEvent.Subtitle.Kind.TRANSLATION_PARTIAL -> SubtitleKind.TRANSLATION_PARTIAL
    AgentEvent.Subtitle.Kind.TRANSLATION_FINAL -> SubtitleKind.TRANSLATION_FINAL
}
