package com.verba.interpretation.ui.interpretation

data class InterpretationDisplayBubble(
    val key: String,
    val text: String,
    val role: Role,
) {
    enum class Role { SOURCE, TRANSLATION }

    companion object {
        fun map(
            sourceSegments: List<String>,
            translationSegments: List<String>,
        ): List<InterpretationDisplayBubble> = buildList {
            addAll(sourceSegments.toBubbles(Role.SOURCE))
            addAll(translationSegments.toBubbles(Role.TRANSLATION))
        }

        private fun List<String>.toBubbles(role: Role): List<InterpretationDisplayBubble> =
            mapIndexedNotNull { index, text ->
                text.takeUnless(String::isBlank)?.let {
                    InterpretationDisplayBubble("${role.name.lowercase()}-$index", it, role)
                }
            }
    }
}
