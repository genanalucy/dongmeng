package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpretationDisplayBubbleTest {
    @Test fun pairsLatestTurnSegmentsByIndexWithStableSentenceKeys() {
        val bubbles = InterpretationDisplayBubble.map(
            turnId = 9,
            sourceSegments = listOf("甲。", "", "乙"),
            translationSegments = listOf("One.", " ", " Two"),
        )

        assertEquals(
            listOf(
                InterpretationDisplayBubble("9:0", "甲。", "One."),
                InterpretationDisplayBubble("9:2", "乙", " Two"),
            ),
            bubbles,
        )
    }

    @Test fun usesFixedPendingCopyForSourceWithoutTranslation() {
        assertEquals(
            listOf(InterpretationDisplayBubble("3:0", "甲。", "正在翻译…")),
            InterpretationDisplayBubble.map(turnId = 3, sourceSegments = listOf("甲。"), translationSegments = emptyList()),
        )
    }

    @Test fun rendersTranslationOnlySegmentWithoutInventingSource() {
        assertEquals(
            listOf(InterpretationDisplayBubble("4:0", null, "译文。")),
            InterpretationDisplayBubble.map(turnId = 4, sourceSegments = emptyList(), translationSegments = listOf("译文。")),
        )
    }
}
