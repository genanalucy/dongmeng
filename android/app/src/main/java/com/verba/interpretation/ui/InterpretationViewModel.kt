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
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.audio.TtsPlayer
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.AgentSocket
import com.verba.interpretation.protocol.EndpointSettings
import com.verba.interpretation.history.CompletedTurn
import com.verba.interpretation.history.LocalHistoryRepository
import com.verba.interpretation.protocol.TranslationSessionEndReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InterpretationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InterpretationUiState())
    val state: StateFlow<InterpretationUiState> = mutableState.asStateFlow()
    private val microphone = MicrophoneCapture(application)
    private val endpointSettings = EndpointSettings(application)
    private val player = TtsPlayer()
    private val cloudSessions = TranslationSessionCoordinator(
        CloudApi(CloudEndpointSettings(application), KeystoreTokenStore(application), SharedPreferencesInstallationIdStore(application)),
        viewModelScope,
    )
    private val mutableCloudSessionCloseFailure = MutableStateFlow<CloudSessionFailureCode?>(null)
    val cloudSessionCloseFailure: StateFlow<CloudSessionFailureCode?> = mutableCloudSessionCloseFailure.asStateFlow()
    private val sessions = TurnSessionCoordinator<AgentSocket>()
    private val history = LocalHistoryRepository.create(application)
    private var localHistorySessionId: String? = null
    private val historyCaptureMutex = Mutex()
    private val actionLock = Any()
    private var cloudGrant: TranslationSessionGrant? = null
    private var pendingGrantOpen: OpenHandle? = null
    private var operationGeneration = 0L
    private var nextTurnId = 1L

    init {
        observeInterpretationCloudSessionFailures(
            cloudSessions.endFailures,
            viewModelScope,
            mutableCloudSessionCloseFailure,
        )
    }

    fun setLanguages(sourceLanguage: String, targetLanguage: String) {
        if (mutableState.value.phase != SessionPhase.IDLE || !supportsTranslationPair(sourceLanguage, targetLanguage)) return
        mutableState.update { it.copy(sourceLanguage = sourceLanguage, targetLanguage = targetLanguage) }
    }

    fun swapLanguages() {
        val snapshot = mutableState.value
        setLanguages(snapshot.targetLanguage, snapshot.sourceLanguage)
    }

    fun setRoute(route: PlaybackRoute) { mutableState.update { it.copy(route = route) } }

    fun start() = synchronized(actionLock) {
        if (mutableState.value.phase != SessionPhase.IDLE) return
        mutableState.update { it.copy(phase = SessionPhase.STARTING, turns = emptyList(), error = null, sessionEndReason = null) }
        openTurn(isResume = false)
    }

    fun pause() {
        if (mutableState.value.phase != SessionPhase.RUNNING && mutableState.value.phase != SessionPhase.STARTING) return
        microphone.stop()
        sessions.pauseAndFinishSessions().forEach { it.finish() }
        mutableState.update { it.copy(phase = SessionPhase.PAUSED) }
    }

    fun resume() {
        if (mutableState.value.phase != SessionPhase.PAUSED) return
        mutableState.update { it.copy(phase = SessionPhase.STARTING, error = null) }
        val grant = cloudGrant ?: run {
            fail("云端翻译会话已失效，请重新开始。")
            return
        }
        openTurn(isResume = true, grant = grant)
    }

    fun finish() {
        val phase = mutableState.value.phase
        if (phase == SessionPhase.IDLE || phase == SessionPhase.STOPPING) return
        microphone.stop()
        mutableState.update { it.copy(phase = SessionPhase.STOPPING) }
        sessions.stopAndFinishSessions().forEach { it.finish() }
        becomeIdleIfDrained()
    }

    fun clearError() {
        cancel()
    }

    fun cancel() = synchronized(actionLock) {
        invalidatePendingGrantOpen()
        microphone.stop()
        cancelAllSessions()
        endCloudSession()
        player.stop()
        mutableState.update { it.copy(phase = SessionPhase.IDLE, error = null, sessionEndReason = null) }
    }

    fun microphonePermissionDenied() {
        fail("未授予麦克风权限。")
    }

    private fun startMicrophone(isResume: Boolean) {
        when (val result = microphone.start(
            onPacket = { packet ->
                if (!sessions.sendToActive { it.sendAudio(packet) }) {
                    fail("音频包无法发送，连接尚未就绪或已断开。")
                }
            },
            onError = ::fail,
        )) {
            CaptureResult.Started -> Unit
            CaptureResult.AlreadyRunning -> fail("麦克风已在录音。")
            CaptureResult.Stopped -> fail(if (isResume) "麦克风无法恢复。" else "麦克风未启动。")
            is CaptureResult.Error -> fail(result.message)
        }
    }

    /** Creates and starts the replacement before atomically routing input to it and finishing the old Turn. */
    private fun openTurn(isResume: Boolean, grant: TranslationSessionGrant? = null) {
        val existing = grant ?: cloudGrant
        if (existing != null) {
            startTurn(isResume, existing)
            return
        }
        if (pendingGrantOpen != null) return
        val generation = operationGeneration
        pendingGrantOpen = cloudSessions.open(
            onGranted = { created -> synchronized(actionLock) {
                pendingGrantOpen = null
                if (generation != operationGeneration || mutableState.value.phase != SessionPhase.STARTING) {
                    cloudSessions.end(created.sessionId)
                    return@synchronized
                }
                cloudGrant = created
                startTurn(isResume, created)
            } },
            onFailure = { message -> synchronized(actionLock) {
                pendingGrantOpen = null
                if (generation == operationGeneration) fail(message)
            } },
        )
    }

    private fun startTurn(isResume: Boolean, grant: TranslationSessionGrant) {
        if (mutableState.value.phase != SessionPhase.STARTING) {
            cloudSessions.end(grant.sessionId)
            if (cloudGrant?.sessionId == grant.sessionId) cloudGrant = null
            return
        }
        val snapshot = mutableState.value
        val turn = SubtitleTurn(nextTurnId++, snapshot.sourceLanguage, snapshot.targetLanguage)
        lateinit var socket: AgentSocket
        socket = AgentSocket(
            endpointSettings = endpointSettings,
            onEvent = { event -> handleEvent(turn.id, event) },
            onTts = { pcm -> playQueued(sessions.offerTts(turn.id, pcm)) },
            onFailure = { message -> handleSessionFailure(turn.id, message) },
        )
        sessions.add(turn.id, socket)
        mutableState.update { it.copy(turns = it.turns + turn) }
        if (!socket.start(snapshot.sourceLanguage, snapshot.targetLanguage, grant)) {
            socket.cancel()
            fail("无法创建翻译会话。")
            return
        }
        val activation = sessions.activate(turn.id) ?: run {
            socket.cancel()
            return
        }
        activation.previous?.finish()
        if (activation.ready) markRunningIfStarting()
        startMicrophone(isResume)
    }

    private fun handleEvent(turnId: Long, event: AgentEvent) {
        if (!sessions.contains(turnId)) return
        when (event) {
            AgentEvent.Ready -> if (sessions.markReady(turnId)) markRunningIfStarting()
            AgentEvent.Finished -> {
                captureCompletedTurn(turnId)
                markTurnFinished(turnId)
                playQueued(sessions.sessionFinished(turnId))
                becomeIdleIfDrained()
            }
            is AgentEvent.Subtitle -> mutableState.update { current ->
                current.copy(turns = current.turns.map { turn ->
                    if (turn.id == turnId) turn.withSubtitle(event.kind.toSubtitleKind(), event.text) else turn
                })
            }
            is AgentEvent.SessionTerminated -> terminateSession(event.reason)
            is AgentEvent.Error -> handleSessionFailure(turnId, "${event.code}: ${event.message}")
        }
    }

    private fun playQueued(first: TurnSessionCoordinator.Playback?) {
        var playback = first
        while (playback != null) {
            val result = player.play(playback.pcm, mutableState.value.route)
            if (result.isFailure) {
                fail(result.exceptionOrNull()?.message ?: "TTS 播放失败。")
                return
            }
            playback = sessions.playbackFinished()
        }
        becomeIdleIfDrained()
    }

    private fun handleSessionFailure(turnId: Long, message: String) {
        if (sessions.isActive(turnId)) {
            fail(message)
            return
        }
        markTurnFinished(turnId)
        playQueued(sessions.sessionFinished(turnId))
        becomeIdleIfDrained()
    }

    private fun markRunningIfStarting() {
        mutableState.update {
            it.copy(phase = if (it.phase == SessionPhase.STARTING) SessionPhase.RUNNING else it.phase)
        }
    }

    private fun captureCompletedTurn(turnId: Long) {
        val turn = mutableState.value.turns.firstOrNull { it.id == turnId } ?: return
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
                        mode = "solo",
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

    private fun markTurnFinished(turnId: Long) {
        mutableState.update { current ->
            current.copy(turns = current.turns.map { if (it.id == turnId) it.copy(finished = true) else it })
        }
    }

    private fun becomeIdleIfDrained() {
        if (!sessions.canBecomeIdle()) return
        player.stop()
        mutableState.update { current ->
            if (current.phase == SessionPhase.STOPPING) current.copy(phase = SessionPhase.IDLE) else current
        }
        endCloudSession()
    }

    private fun terminateSession(reason: TranslationSessionEndReason) = synchronized(actionLock) {
        if (mutableState.value.sessionEndReason != null) return
        invalidatePendingGrantOpen()
        microphone.stop()
        cancelAllSessions()
        endCloudSession()
        player.stop()
        mutableState.update { it.withTerminatedSession(reason) }
    }

    private fun fail(message: String) = synchronized(actionLock) {
        if (mutableState.value.sessionEndReason != null) return
        invalidatePendingGrantOpen()
        microphone.stop()
        cancelAllSessions()
        endCloudSession()
        player.stop()
        mutableState.update { it.copy(phase = SessionPhase.ERROR, error = message, sessionEndReason = null) }
    }

    private fun cancelAllSessions() {
        sessions.cancelAll().forEach { it.cancel() }
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

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}

private fun AgentEvent.Subtitle.Kind.toSubtitleKind(): SubtitleKind = when (this) {
    AgentEvent.Subtitle.Kind.SOURCE_PARTIAL -> SubtitleKind.SOURCE_PARTIAL
    AgentEvent.Subtitle.Kind.SOURCE_FINAL -> SubtitleKind.SOURCE_FINAL
    AgentEvent.Subtitle.Kind.TRANSLATION_PARTIAL -> SubtitleKind.TRANSLATION_PARTIAL
    AgentEvent.Subtitle.Kind.TRANSLATION_FINAL -> SubtitleKind.TRANSLATION_FINAL
}
