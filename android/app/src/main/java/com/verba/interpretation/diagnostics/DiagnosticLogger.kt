package com.verba.interpretation.diagnostics

import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.TranslationProvider
import com.verba.interpretation.ui.FaceToFaceState

data class DiagnosticEntry(val elapsedMillis: Long, val clockTime: String, val category: String, val message: String)

/** Renders only DiagnosticEntry values produced by the whitelist-only logger seam. */
fun renderDiagnosticLog(entries: List<DiagnosticEntry>): String = entries.joinToString(
    separator = "\n",
    prefix = "Verba Debug 诊断日志（安全元数据）\n",
) { "+${it.elapsedMillis}ms ${it.clockTime} [${it.category}] ${it.message}" }

/**
 * Whitelist-only, process-local diagnostic seam. Implementations must never accept user or
 * transport payloads, so callers cannot accidentally copy credentials, audio, or transcript text.
 */
interface DiagnosticLogger {
    fun faceAction(action: FaceAction)
    fun state(state: FaceToFaceState)
    fun runtime(provider: TranslationProvider, automaticSupported: Boolean)
    fun socketStart(provider: TranslationProvider, automatic: Boolean, sourceLanguage: String, targetLanguage: String)
    fun agentEvent(event: AgentEvent)
    fun socketLifecycle(stage: SocketLifecycle)
    fun socketFailure(reason: SocketFailure)
    fun microphone(outcome: MicrophoneOutcome)
    fun entries(): List<DiagnosticEntry>
    fun clear()
}

enum class FaceAction { MODE_MANUAL, MODE_AUTO, ENABLE_AUTO, START_AUTO, RESUME_AUTO, STOP_AUTO, PERMISSION_GRANTED, PERMISSION_DENIED }
enum class SocketLifecycle { OPENING, OPENED, CLOSED, CANCELLED, FINISHED, PROTOCOL_REJECTED }
enum class SocketFailure { TRANSPORT, START_SEND, INVALID_AUDIO_METADATA, VIEW_MODEL, CAPTURE, PLAYBACK }
enum class MicrophoneOutcome { STARTED, ALREADY_RUNNING, STOPPED, ERROR, STOP_REQUESTED }

/** Resolved from debug/release source sets: bounded collector in debug and no-op in release. */
object DiagnosticLog : DiagnosticLogger {
    override fun faceAction(action: FaceAction) = implementation.faceAction(action)
    override fun state(state: FaceToFaceState) = implementation.state(state)
    override fun runtime(provider: TranslationProvider, automaticSupported: Boolean) = implementation.runtime(provider, automaticSupported)
    override fun socketStart(provider: TranslationProvider, automatic: Boolean, sourceLanguage: String, targetLanguage: String) = implementation.socketStart(provider, automatic, sourceLanguage, targetLanguage)
    override fun agentEvent(event: AgentEvent) = implementation.agentEvent(event)
    override fun socketLifecycle(stage: SocketLifecycle) = implementation.socketLifecycle(stage)
    override fun socketFailure(reason: SocketFailure) = implementation.socketFailure(reason)
    override fun microphone(outcome: MicrophoneOutcome) = implementation.microphone(outcome)
    override fun entries(): List<DiagnosticEntry> = implementation.entries()
    override fun clear() = implementation.clear()
}

internal interface DiagnosticLogImplementation : DiagnosticLogger
