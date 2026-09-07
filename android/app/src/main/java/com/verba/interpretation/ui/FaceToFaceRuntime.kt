package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.audio.CaptureResult
import com.verba.interpretation.audio.MicrophoneCapture
import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.audio.TtsPlayer
import com.verba.interpretation.cloud.TranslationSessionGrant
import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.AgentSocket
import com.verba.interpretation.protocol.EndpointSettings

/** Device/network boundary; the ViewModel and coordinator retain all lifecycle decisions. */
interface FaceToFaceSocket {
    fun start(source: String, target: String, grant: TranslationSessionGrant): Boolean
    fun sendAudio(packet: ByteArray): Boolean
    fun finish()
    fun cancel()
}

interface FaceToFaceRuntime {
    fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray) -> Unit, onFailure: (String) -> Unit): FaceToFaceSocket
    fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit): CaptureResult
    fun stopCapture()
    fun play(pcm: ByteArray, route: PlaybackRoute): Result<Unit>
    fun awaitDrained(): Result<Unit>
    fun stopPlayback()
}

internal class AndroidFaceToFaceRuntime(application: Application) : FaceToFaceRuntime {
    private val microphone = MicrophoneCapture(application)
    private val endpointSettings = EndpointSettings(application)
    private val player = TtsPlayer()

    override fun createSocket(onEvent: (AgentEvent) -> Unit, onTts: (ByteArray) -> Unit, onFailure: (String) -> Unit): FaceToFaceSocket {
        val socket = AgentSocket(endpointSettings, onEvent = onEvent, onTts = onTts, onFailure = onFailure)
        return object : FaceToFaceSocket {
            override fun start(source: String, target: String, grant: TranslationSessionGrant) = socket.start(source, target, grant)
            override fun sendAudio(packet: ByteArray) = socket.sendAudio(packet)
            override fun finish() { socket.finish() }
            override fun cancel() { socket.cancel() }
        }
    }

    override fun startCapture(onPacket: (ByteArray) -> Unit, onError: (String) -> Unit, onLevel: (Float) -> Unit) =
        microphone.start(onPacket, onError, onLevel)
    override fun stopCapture() { microphone.stop() }
    override fun play(pcm: ByteArray, route: PlaybackRoute) = player.play(pcm, route)
    override fun awaitDrained() = player.awaitDrained()
    override fun stopPlayback() = player.stop()
}
