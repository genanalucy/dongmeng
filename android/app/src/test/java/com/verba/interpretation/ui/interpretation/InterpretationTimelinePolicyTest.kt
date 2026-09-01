package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpretationTimelinePolicyTest {
    @Test
    fun latestIndexUsesFinalBubble() {
        assertEquals(2, interpretationTimelineLatestIndex(bubbleCount = 3, hasError = false))
    }

    @Test
    fun latestIndexUsesErrorAfterBubbles() {
        assertEquals(3, interpretationTimelineLatestIndex(bubbleCount = 3, hasError = true))
    }

    @Test
    fun scrollIndexIsNullForEmptyTimelineAndTargetsActualFinalItem() {
        assertEquals(null, interpretationTimelineScrollIndex(bubbleCount = 0, hasError = false))
        assertEquals(0, interpretationTimelineScrollIndex(bubbleCount = 0, hasError = true))
        assertEquals(3, interpretationTimelineScrollIndex(bubbleCount = 3, hasError = true))
    }

    @Test
    fun bubbleAndErrorChangesAlwaysCountAsAnUpdate() {
        assertEquals(
            1,
            interpretationTimelineUpdateCount(
                previousToken = listOf("bubble:one"),
                currentToken = listOf("bubble:one", "error:offline"),
            ),
        )
        assertEquals(
            1,
            interpretationTimelineUpdateCount(
                previousToken = listOf("error:offline"),
                currentToken = listOf("bubble:one", "error:offline"),
            ),
        )
    }
}
