package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationDisplayBubbleTest {
    @Test fun mapsIndependentlySplitSegmentsToNonEmptyUnpairedBubblesWithStableUniqueKeys() {
        val bubbles = InterpretationDisplayBubble.map(
            sourceSegments = listOf("甲。", "", "乙"),
            translationSegments = listOf("One.", " ", " Two"),
        )

        assertEquals(
            listOf(
                InterpretationDisplayBubble("source-0", "甲。", InterpretationDisplayBubble.Role.SOURCE),
                InterpretationDisplayBubble("source-2", "乙", InterpretationDisplayBubble.Role.SOURCE),
                InterpretationDisplayBubble("translation-0", "One.", InterpretationDisplayBubble.Role.TRANSLATION),
                InterpretationDisplayBubble("translation-2", " Two", InterpretationDisplayBubble.Role.TRANSLATION),
            ),
            bubbles,
        )
        assertEquals(bubbles.map { it.key }.distinct().size, bubbles.size)
        assertTrue(bubbles.none { it.text.isBlank() })
    }
}
