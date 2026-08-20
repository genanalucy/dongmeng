package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTurnTest {
    @Test fun aggregatesMultipleFinalsAndKeepsCurrentPartial() {
        val turn = SubtitleTurn(1, "zh", "en")
            .withSubtitle(SubtitleKind.SOURCE_FINAL, "第一句")
            .withSubtitle(SubtitleKind.SOURCE_FINAL, "第二句")
            .withSubtitle(SubtitleKind.SOURCE_PARTIAL, "第三")
            .withSubtitle(SubtitleKind.TRANSLATION_FINAL, "First")
            .withSubtitle(SubtitleKind.TRANSLATION_FINAL, "Second")
            .withSubtitle(SubtitleKind.TRANSLATION_PARTIAL, "Third")

        assertEquals("第一句 第二句 第三", turn.sourceText)
        assertEquals("First Second Third", turn.translatedText)
    }

    @Test fun finalReplacesItsPartialButNotEarlierFinals() {
        val turn = SubtitleTurn(1, "zh", "en")
            .withSubtitle(SubtitleKind.SOURCE_FINAL, "已完成")
            .withSubtitle(SubtitleKind.SOURCE_PARTIAL, "草稿")
            .withSubtitle(SubtitleKind.SOURCE_FINAL, "新完成")

        assertEquals("已完成 新完成", turn.sourceText)
        assertEquals("", turn.sourcePartial)
    }
}
