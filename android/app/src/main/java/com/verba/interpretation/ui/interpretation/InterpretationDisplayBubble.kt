package com.verba.interpretation.ui.interpretation

data class InterpretationDisplayBubble(
    val key: String,
    val sourceText: String?,
    val translationText: String,
) {
    companion object {
        fun map(
            turnId: Long,
            sourceSegments: List<String>,
            translationSegments: List<String>,
        ): List<InterpretationDisplayBubble> =
            (0 until maxOf(sourceSegments.size, translationSegments.size)).mapNotNull { index ->
                val source = sourceSegments.getOrNull(index)?.takeUnless(String::isBlank)
                val translation = translationSegments.getOrNull(index)?.takeUnless(String::isBlank)
                when {
                    source != null -> InterpretationDisplayBubble("$turnId:$index", source, translation ?: "正在翻译…")
                    translation != null -> InterpretationDisplayBubble("$turnId:$index", null, translation)
                    else -> null
                }
            }
    }
}
