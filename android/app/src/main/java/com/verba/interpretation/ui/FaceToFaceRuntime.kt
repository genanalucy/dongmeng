package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.MicrophoneCapture
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.audio.TtsPlayer
import com.verba.interpretation.cloud.TranslationSessionGrant
import com.verba.interpretation.diagnostics.DiagnosticLog
import com.verba.interpretation.diagnostics.MicrophoneOutcome
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.AgentSocket
import com.verba.interpretation.protocol.EndpointSettings
import com.verba.interpretation.protocol.TranslationSettingsStore

/** Device/network boundary; the ViewModel and coordinator retain all lifecycle decisions. */
interface FaceToFaceSocket {
    /** Fixed when this transport is created; never reread settings while starting it. */
    val automaticLanguageDetectionSupported: Boolean get() = false

    fun start(source: String, target: String, grant: TranslationSessionGrant, candidateLanguages: List<String> = emptyList()): Boolean
    fun sendAudio(packet: ByteArray): Boolean
    fun finish()
    fun cancel()
}

interface FaceToFaceRuntime {
    fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray, Long?, String?) -> Unit, onFailure: (String) -> Unit): FaceToFaceSocket
    fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit): CaptureResult
    fun stopCapture()
    fun play(pcm: ByteArray, route: PlaybackRoute): Result<Unit>
    fun awaitDrained(): Result<Unit>
    fun stopPlayback()
}

internal class AndroidFaceToFaceRuntime(application: Application) : FaceToFaceRuntime {
    private val microphone = MicrophoneCapture(application)
    private val endpointSettings = EndpointSettings(application)
    private val translationSettings = TranslationSettingsStore(application)
    private val player = TtsPlayer()

    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray, Long?, String?) -> Unit, onFailure: (String) -> Unit): FaceToFaceSocket {
        // A face-to-face transport must use one settings image for both automatic LID
        // and its start payload. Reading the store again in AgentSocket can race a setting change.
        val settingsSnapshot = translationSettings.load()
        val automaticSupported = settingsSnapshot.provider == com.verba.interpretation.protocol.TranslationProvider.AZURE
        DiagnosticLog.runtime(settingsSnapshot.provider, automaticSupported)
        val socket = AgentSocket(
            endpointSettings = endpointSettings,
            translationSettings = { settingsSnapshot },
            onEvent = onEvent,
            onTts = onTts,
            onFailure = onFailure,
        )
        return object : FaceToFaceSocket {
            override val automaticLanguageDetectionSupported = automaticSupported

            override fun start(source: String, target: String, grant: TranslationSessionGrant, candidateLanguages: List<String>): Boolean {
                DiagnosticLog.socketStart(settingsSnapshot.provider, candidateLanguages.isNotEmpty(), source, target)
                return socket.start(source, target, grant, candidateLanguages)
            }
            override fun sendAudio(packet: ByteArray) = socket.sendAudio(packet)
            override fun finish() { socket.finish() }
            override fun cancel() { socket.cancel() }
        }
    }

    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit): CaptureResult {
        val result = microphone.start(onPacket, onError, onLevel)
        DiagnosticLog.microphone(
            when (result) {
                CaptureResult.Started -> MicrophoneOutcome.STARTED
                CaptureResult.AlreadyRunning -> MicrophoneOutcome.ALREADY_RUNNING
                CaptureResult.Stopped -> MicrophoneOutcome.STOPPED
                is CaptureResult.Error -> MicrophoneOutcome.ERROR
            },
        )
        return result
    }
    override fun stopCapture() { DiagnosticLog.microphone(MicrophoneOutcome.STOP_REQUESTED); microphone.stop() }
    override fun play(pcm: ByteArray, route: PlaybackRoute) = player.play(pcm, route)
    override fun awaitDrained() = player.awaitDrained()
    override fun stopPlayback() = player.stop()
}
