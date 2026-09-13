package com.verba.interpretation.diagnostics

import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.protocol.TranslationProvider
import com.verba.interpretation.ui.FaceToFaceState

internal val implementation: DiagnosticLogImplementation = object : DiagnosticLogImplementation {
    override fun faceAction(action: FaceAction) = Unit
    override fun state(state: FaceToFaceState) = Unit
    override fun runtime(provider: TranslationProvider, automaticSupported: Boolean) = Unit
    override fun socketStart(provider: TranslationProvider, automatic: Boolean, sourceLanguage: String, targetLanguage: String) = Unit
    override fun agentEvent(event: AgentEvent) = Unit
    override fun socketLifecycle(stage: SocketLifecycle) = Unit
    override fun socketFailure(reason: SocketFailure) = Unit
    override fun microphone(outcome: MicrophoneOutcome) = Unit
    override fun entries(): List<DiagnosticEntry> = emptyList()
    override fun clear() = Unit
}
