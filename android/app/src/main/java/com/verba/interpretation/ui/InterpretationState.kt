package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute

enum class SessionPhase { IDLE, STARTING, RUNNING, PAUSED, STOPPING, ERROR }

data class SubtitleTurn(
    val id: Long,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceFinals: List<String> = emptyList(),
    val sourcePartial: String = "",
    val translationFinals: List<String> = emptyList(),
    val translationPartial: String = "",
    val finished: Boolean = false,
) {
    val sourceText: String get() = aggregateSubtitle(sourceFinals, sourcePartial)
    val translatedText: String get() = aggregateSubtitle(translationFinals, translationPartial)

    fun withSubtitle(kind: SubtitleKind, text: String): SubtitleTurn = when (kind) {
        SubtitleKind.SOURCE_PARTIAL -> copy(sourcePartial = text)
        SubtitleKind.SOURCE_FINAL -> copy(sourceFinals = sourceFinals + text, sourcePartial = "")
        SubtitleKind.TRANSLATION_PARTIAL -> copy(translationPartial = text)
        SubtitleKind.TRANSLATION_FINAL -> copy(translationFinals = translationFinals + text, translationPartial = "")
    }
}

enum class SubtitleKind { SOURCE_PARTIAL, SOURCE_FINAL, TRANSLATION_PARTIAL, TRANSLATION_FINAL }

internal fun aggregateSubtitle(finals: List<String>, partial: String): String = buildList {
    addAll(finals)
    if (partial.isNotBlank()) add(partial)
}.joinToString(" ")

data class InterpretationUiState(
    val phase: SessionPhase = SessionPhase.IDLE,
    val sourceLanguage: String = "zh",
    val targetLanguage: String = "en",
    val route: PlaybackRoute = PlaybackRoute.RIGHT,
    val turns: List<SubtitleTurn> = emptyList(),
    val error: String? = null,
)

sealed interface SessionAction { data object Start : SessionAction; data object Ready : SessionAction; data object Pause : SessionAction; data object Resume : SessionAction; data object Finish : SessionAction; data object Drained : SessionAction; data object Fail : SessionAction; data object Reset : SessionAction }

object InterpretationStateMachine {
    fun reduce(phase: SessionPhase, action: SessionAction): SessionPhase = when (phase) {
        SessionPhase.IDLE -> when (action) { SessionAction.Start -> SessionPhase.STARTING; else -> phase }
        SessionPhase.STARTING -> when (action) { SessionAction.Ready -> SessionPhase.RUNNING; SessionAction.Finish -> SessionPhase.STOPPING; SessionAction.Fail -> SessionPhase.ERROR; else -> phase }
        SessionPhase.RUNNING -> when (action) { SessionAction.Pause -> SessionPhase.PAUSED; SessionAction.Finish -> SessionPhase.STOPPING; SessionAction.Fail -> SessionPhase.ERROR; else -> phase }
        SessionPhase.PAUSED -> when (action) { SessionAction.Resume -> SessionPhase.STARTING; SessionAction.Finish -> SessionPhase.STOPPING; SessionAction.Fail -> SessionPhase.ERROR; else -> phase }
        SessionPhase.STOPPING -> when (action) { SessionAction.Drained -> SessionPhase.IDLE; SessionAction.Fail -> SessionPhase.ERROR; else -> phase }
        SessionPhase.ERROR -> if (action == SessionAction.Reset) SessionPhase.IDLE else phase
    }
}
