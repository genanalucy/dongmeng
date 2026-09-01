package com.verba.interpretation.ui.display

data class SplitDisplayText(
    val source: List<String>,
    val translation: List<String>,
)

/** Splits display-only subtitle text without changing its aggregation or transport representation. */
object DisplaySentenceSplitter {
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        var segmentStart = 0
        var index = 0
        while (index < text.length) {
            val character = text[index]
            val delimiterEnd = when (character) {
                '。', '.' -> index + 1
                '，', ',' -> commaRunEnd(text, index, character)
                else -> null
            }
            if (delimiterEnd != null) {
                text.substring(segmentStart, delimiterEnd).takeIf { it.isNotEmpty() }?.let(segments::add)
                segmentStart = delimiterEnd
                index = delimiterEnd
            } else {
                index++
            }
        }
        text.substring(segmentStart).takeIf { it.isNotEmpty() }?.let(segments::add)
        return segments
    }

    fun split(source: String, translation: String): SplitDisplayText = SplitDisplayText(
        source = split(source),
        translation = split(translation),
    )

    private fun commaRunEnd(text: String, start: Int, comma: Char): Int? {
        var end = start
        while (end < text.length && text[end] == comma) end++
        return end.takeIf { end - start >= 3 }
    }
}
