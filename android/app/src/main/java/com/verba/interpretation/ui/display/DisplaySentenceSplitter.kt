package com.verba.interpretation.ui.display

data class SplitDisplayText(
    val source: List<String>,
    val translation: List<String>,
)

/** Splits display-only subtitle text without changing its aggregation or transport representation. */
object DisplaySentenceSplitter {
    /** Display-only cap for unpaired rows: 42 CJK or 90 non-CJK characters. */
    const val MAX_CJK_DISPLAY_CHARS = 42
    const val MAX_LATIN_DISPLAY_CHARS = 90

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val segments = mutableListOf<String>()
        var segmentStart = 0
        text.forEachIndexed { index, character ->
            if (character == '。' || character == '.') {
                text.substring(segmentStart, index + 1).takeIf { it.isNotEmpty() }?.let(segments::add)
                segmentStart = index + 1
            }
        }
        text.substring(segmentStart).takeIf { it.isNotEmpty() }?.let(segments::add)
        return segments
    }

    fun splitForVisualCap(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val cap = if (text.any(::isCjk)) MAX_CJK_DISPLAY_CHARS else MAX_LATIN_DISPLAY_CHARS
        return buildList {
            var start = 0
            while (text.length - start > cap) {
                val limit = start + cap
                val delimiter = (limit - 1 downTo start).firstOrNull { text[it] in SAFE_BREAK_DELIMITERS }
                val end = if (delimiter == null) limit else delimiter + 1
                add(text.substring(start, end))
                start = end
            }
            text.substring(start).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun isCjk(character: Char): Boolean = character.code in 0x4E00..0x9FFF

    private val SAFE_BREAK_DELIMITERS = setOf('，', ',', ' ', '\n')

    fun split(source: String, translation: String): SplitDisplayText = SplitDisplayText(
        source = split(source),
        translation = split(translation),
    )

}
