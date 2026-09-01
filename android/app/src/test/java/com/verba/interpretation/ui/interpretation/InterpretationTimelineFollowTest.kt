package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertEquals
import org.junit.Test

class InterpretationTimelineFollowTest {
    @Test
    fun latestIndexIncludesTheOptionalErrorRow() {
        assertEquals(1, interpretationTimelineLatestIndex(bubbleCount = 1, hasError = true))
        assertEquals(0, interpretationTimelineLatestIndex(bubbleCount = 0, hasError = false))
    }

    @Test
    fun partialBubbleAndErrorChangesCountAsTranscriptUpdates() {
        val previous = listOf("1:source-partial:你好:正在翻译…")
        val partialChanged = listOf("1:source-partial:你好，世界:正在翻译…")
        val errorAdded = partialChanged + "error:翻译服务暂时不可用，请重试或重新开始。"

        assertEquals(1, interpretationTimelineUpdateCount(previous, partialChanged))
        assertEquals(1, interpretationTimelineUpdateCount(partialChanged, errorAdded))
    }
}
