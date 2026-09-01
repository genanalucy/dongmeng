package com.verba.interpretation.ui.interpretation

import com.verba.interpretation.ui.display.EventBoundaryDisplay

data class InterpretationDisplayBubble(
    val key: String,
    val sourceText: String?,
    val translationText: String,
) {
    companion object {
        fun map(
            turnId: Long,
            sourceFinals: List<String>,
            sourcePartial: String,
            translationFinals: List<String>,
            translationPartial: String,
        ): List<InterpretationDisplayBubble> = EventBoundaryDisplay.rows(
            sourceFinals = sourceFinals,
            sourcePartial = sourcePartial,
            translationFinals = translationFinals,
            translationPartial = translationPartial,
        ).map { row -> InterpretationDisplayBubble("$turnId:${row.key}", row.sourceText, row.translationText) }
    }
}
