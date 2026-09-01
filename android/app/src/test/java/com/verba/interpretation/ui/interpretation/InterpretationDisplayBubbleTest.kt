package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpretationDisplayBubbleTest {
    @Test fun pairsMatchingFinalEventsWithStableKeys() {
        val bubbles = InterpretationDisplayBubble.map(
            turnId = 9,
            sourceFinals = listOf("甲。", "乙"),
            sourcePartial = "",
            translationFinals = listOf("One.", " Two"),
            translationPartial = "",
        )

        assertEquals(
            listOf(
                InterpretationDisplayBubble("9:0", "甲。", "One."),
                InterpretationDisplayBubble("9:1", "乙", " Two"),
            ),
            bubbles,
        )
    }

    @Test fun usesFixedPendingCopyForSourceWithoutTranslation() {
        assertEquals(
            listOf(InterpretationDisplayBubble("3:0", "甲。", "正在翻译…")),
            InterpretationDisplayBubble.map(3, listOf("甲。"), "", emptyList(), ""),
        )
    }

    @Test fun rendersTranslationOnlyEventWithoutInventingSource() {
        assertEquals(
            listOf(InterpretationDisplayBubble("4:0", null, "译文。")),
            InterpretationDisplayBubble.map(4, emptyList(), "", listOf("译文。"), ""),
        )
    }
}
