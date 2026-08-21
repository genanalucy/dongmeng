package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.MicrophoneCapture
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.audio.TtsPlayer
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.AgentSocket
import com.verba.interpretation.protocol.EndpointSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class InterpretationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(InterpretationUiState())
    val state: StateFlow<InterpretationUiState> = mutableState.asStateFlow()
    private val microphone = MicrophoneCapture(application)
    private val endpointSettings = EndpointSettings(application)
    private val player = TtsPlayer()
    private val sessions = TurnSessionCoordinator<AgentSocket>()
    private var nextTurnId = 1L

    fun setLanguages(sourceLanguage: String, targetLanguage: String) {
        if (mutableState.value.phase != SessionPhase.IDLE || !supportsTranslationPair(sourceLanguage, targetLanguage)) return
        mutableState.update { it.copy(sourceLanguage = sourceLanguage, targetLanguage = targetLanguage) }
    }

    fun swapLanguages() {
        val snapshot = mutableState.value
        setLanguages(snapshot.targetLanguage, snapshot.sourceLanguage)
    }

    fun setRoute(route: PlaybackRoute) { mutableState.update { it.copy(route = route) } }

    fun start() {
        if (mutableState.value.phase != SessionPhase.IDLE) return
        if (!isCurrentProviderAvailable()) {
            mutableState.update { it.copy(error = "法语和越南语的实时服务正在接入，当前仅支持中文与 English 的真实同传。") }
            return
        }
        mutableState.update { it.copy(phase = SessionPhase.STARTING, turns = emptyList(), error = null) }
        if (!openTurn()) return
        startMicrophone(isResume = false)
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
        if (!openTurn()) return
        startMicrophone(isResume = true)
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

    fun cancel() {
        microphone.stop()
        cancelAllSessions()
        player.stop()
        mutableState.update { it.copy(phase = SessionPhase.IDLE, error = null) }
    }

    fun microphonePermissionDenied() {
        fail("未授予麦克风权限。")
    }

    private fun isCurrentProviderAvailable(): Boolean = mutableState.value.let {
        (it.sourceLanguage == "zh" && it.targetLanguage == "en") ||
            (it.sourceLanguage == "en" && it.targetLanguage == "zh")
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
    private fun openTurn(): Boolean {
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
        if (!socket.start(snapshot.sourceLanguage, snapshot.targetLanguage)) {
            fail("无法创建翻译会话。")
            return false
        }
        val activation = sessions.activate(turn.id) ?: run {
            socket.cancel()
            return false
        }
        activation.previous?.finish()
        if (activation.ready) markRunningIfStarting()
        return true
    }

    private fun handleEvent(turnId: Long, event: AgentEvent) {
        when (event) {
            AgentEvent.Ready -> if (sessions.markReady(turnId)) markRunningIfStarting()
            AgentEvent.Finished -> {
                markTurnFinished(turnId)
                playQueued(sessions.sessionFinished(turnId))
                becomeIdleIfDrained()
            }
            is AgentEvent.Subtitle -> mutableState.update { current ->
                current.copy(turns = current.turns.map { turn ->
                    if (turn.id == turnId) turn.withSubtitle(event.kind.toSubtitleKind(), event.text) else turn
                })
            }
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
    }

    private fun fail(message: String) {
        microphone.stop()
        cancelAllSessions()
        player.stop()
        mutableState.update { it.copy(phase = SessionPhase.ERROR, error = message) }
    }

    private fun cancelAllSessions() {
        sessions.cancelAll().forEach { it.cancel() }
    }

    override fun onCleared() {
        microphone.stop()
        cancelAllSessions()
        player.stop()
        super.onCleared()
    }
}

private fun AgentEvent.Subtitle.Kind.toSubtitleKind(): SubtitleKind = when (this) {
    AgentEvent.Subtitle.Kind.SOURCE_PARTIAL -> SubtitleKind.SOURCE_PARTIAL
    AgentEvent.Subtitle.Kind.SOURCE_FINAL -> SubtitleKind.SOURCE_FINAL
    AgentEvent.Subtitle.Kind.TRANSLATION_PARTIAL -> SubtitleKind.TRANSLATION_PARTIAL
    AgentEvent.Subtitle.Kind.TRANSLATION_FINAL -> SubtitleKind.TRANSLATION_FINAL
}
