package com.verba.interpretation.diagnostics

import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.TranslationProvider
import com.verba.interpretation.ui.FaceToFaceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val implementation: DiagnosticLogImplementation = DebugDiagnosticLog()

internal class DebugDiagnosticLog(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val capacity: Int = 200,
) : DiagnosticLogImplementation {
    private val lock = Any()
    private val startedAt = clock()
    private val entries = ArrayDeque<DiagnosticEntry>()

    override fun entries(): List<DiagnosticEntry> = synchronized(lock) { entries.toList() }
    override fun clear() = synchronized(lock) { entries.clear() }
    fun render(): String = entries().joinToString("\n", prefix = "Verba Debug 诊断日志（安全元数据）\n") {
        "+${it.elapsedMillis}ms ${it.clockTime} [${it.category}] ${it.message}"
    }

    private fun add(category: String, message: String) = synchronized(lock) {
        while (entries.size >= capacity) entries.removeFirst()
        val now = clock()
        entries.addLast(DiagnosticEntry(now - startedAt, SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(now)), category, message))
    }

    override fun faceAction(action: FaceAction) = add("action", action.name)
    override fun state(state: FaceToFaceState) = add("state", "mode=${state.mode}; automaticSelected=${state.automaticLanguageDetectionSelected}; phase=${state.phase}; captureActive=${state.captureActive}")
    override fun runtime(provider: TranslationProvider, automaticSupported: Boolean) = add("runtime", "provider=${provider.storedValue}; automaticSupported=$automaticSupported")
    override fun socketStart(provider: TranslationProvider, automatic: Boolean, sourceLanguage: String, targetLanguage: String) = add("socket.start", "provider=${provider.storedValue}; automatic=$automatic; source=$sourceLanguage; target=$targetLanguage")
    override fun agentEvent(event: AgentEvent) = add("agent.event", event.safeDescription())
    override fun socketLifecycle(stage: SocketLifecycle) = add("socket.lifecycle", stage.name)
    override fun socketFailure(reason: SocketFailure) = add("socket.failure", reason.name)
    override fun microphone(outcome: MicrophoneOutcome) = add("microphone", outcome.name)
}

private fun AgentEvent.safeDescription(): String = when (this) {
    AgentEvent.Ready -> "READY"
    AgentEvent.Finished -> "FINISHED"
    is AgentEvent.DetectedLanguage -> "DETECTED_LANGUAGE"
    is AgentEvent.TtsSegment -> "TTS_SEGMENT"
    is AgentEvent.Subtitle -> "SUBTITLE_${kind.name}"
    is AgentEvent.SessionTerminated -> "SESSION_TERMINATED_${reason.name}"
    is AgentEvent.Error -> "ERROR code=$code"
}
