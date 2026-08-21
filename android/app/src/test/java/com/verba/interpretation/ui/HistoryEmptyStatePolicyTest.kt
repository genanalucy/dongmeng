package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryEmptyStatePolicyTest {
    @Test
    fun searchQueryTakesPriorityAndIsTrimmed() {
        assertEquals(
            "没有找到与“meeting”相关的记录",
            HistoryEmptyStatePolicy.message("  meeting  ", HistoryFilter.FACE_TO_FACE),
        )
    }

    @Test
    fun selectedModeGetsSpecificEmptyMessage() {
        assertEquals("还没有同传记录", HistoryEmptyStatePolicy.message("", HistoryFilter.INTERPRETATION))
        assertEquals("还没有面对面翻译记录", HistoryEmptyStatePolicy.message("", HistoryFilter.FACE_TO_FACE))
    }

    @Test
    fun allFilterExplainsHowHistoryIsCreated() {
        assertEquals(
            "完成一次翻译后，记录会保存在这里",
            HistoryEmptyStatePolicy.message("", HistoryFilter.ALL),
        )
    }
}
