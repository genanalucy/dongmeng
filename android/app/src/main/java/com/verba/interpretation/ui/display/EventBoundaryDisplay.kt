package com.verba.interpretation.ui.display

internal const val TRANSLATION_PENDING_COPY = "正在翻译…"

/** A display row derived only from one subtitle event boundary; it never infers bilingual alignment. */
internal data class EventBoundaryDisplayRow(
    val key: String,
    val sourceText: String?,
    val translationText: String,
)

internal object EventBoundaryDisplay {
    fun rows(
        sourceFinals: List<String>,
        sourcePartial: String,
        translationFinals: List<String>,
        translationPartial: String,
    ): List<EventBoundaryDisplayRow> = buildList {
        (0 until maxOf(sourceFinals.size, translationFinals.size)).forEach { index ->
            val source = sourceFinals.getOrNull(index)?.takeUnless(String::isBlank)
            val translation = translationFinals.getOrNull(index)?.takeUnless(String::isBlank)
            when {
                source != null && translation != null -> add(EventBoundaryDisplayRow(index.toString(), source, translation))
                source != null -> addUnpairedSource(index.toString(), source)
                translation != null -> addUnpairedTranslation(index.toString(), translation)
            }
        }
        sourcePartial.takeUnless(String::isBlank)?.let { addUnpairedSource("source-partial", it) }
        translationPartial.takeUnless(String::isBlank)?.let { addUnpairedTranslation("translation-partial", it) }
    }

    private fun MutableList<EventBoundaryDisplayRow>.addUnpairedSource(key: String, text: String) {
        val segments = DisplaySentenceSplitter.splitForVisualCap(text)
        segments.forEachIndexed { index, segment ->
            add(EventBoundaryDisplayRow(segmentKey(key, index, segments.size), segment, TRANSLATION_PENDING_COPY))
        }
    }

    private fun MutableList<EventBoundaryDisplayRow>.addUnpairedTranslation(key: String, text: String) {
        val segments = DisplaySentenceSplitter.splitForVisualCap(text)
        segments.forEachIndexed { index, segment ->
            add(EventBoundaryDisplayRow(segmentKey(key, index, segments.size), null, segment))
        }
    }

    private fun segmentKey(key: String, index: Int, segmentCount: Int): String =
        if (segmentCount == 1) key else "$key:$index"
}
